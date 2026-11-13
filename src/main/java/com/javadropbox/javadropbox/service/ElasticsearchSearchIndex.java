package com.javadropbox.javadropbox.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import co.elastic.clients.util.ObjectBuilder;
import com.javadropbox.javadropbox.config.SearchConfig;
import com.javadropbox.javadropbox.dto.Snippet;
import com.javadropbox.javadropbox.exception.ServiceUnavailableException;
import com.javadropbox.javadropbox.service.IndexableItems.Item;
import com.javadropbox.javadropbox.service.IndexableItems.Stamp;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The {@link SearchIndex} on Elasticsearch, which several app servers can share. Picked with {@code
 * javadropbox.search.backend=elasticsearch}; the service is at {@code
 * javadropbox.search.elasticsearch.url}.
 *
 * <p>It finds and ranks what {@link LuceneSearchIndex} does: the same fields, each analysed the
 * same way (the folded name is worked out here, with Lucene's own analyser), and the same query,
 * clause for clause and boost for boost, in one shard so that every item is scored against the same
 * statistics. Snippets are cut from each hit's stored text by Lucene's highlighter, so they come
 * out as Lucene's own do. (Elasticsearch 8 splits the text with the Lucene it ships, 9.12, and the
 * app splits searches with its own, 10; the two split text by the same Unicode rules.) Each item's
 * size and modification time are kept with it, so a reconcile reads again only the files that
 * differ.
 *
 * <p>The index is named after its format, {@code <index>-<format>}, and reached through an alias,
 * {@code <index>}, which writes require: an index the app didn't make, under that name, is left
 * alone, and one deleted behind its back isn't made again by a write in Elasticsearch's own way,
 * but by the app in its. When a newer version of the app changes the format, it makes its own
 * index, moves the alias to it and deletes the old one. An older version that finds the alias moved
 * stops writing.
 *
 * <p>When Elasticsearch is down, only search is: a search answers 503, and changes wait to be tried
 * again, further apart the longer it lasts. What went wrong is logged once, and shown by the health
 * check, until a change or a search gets through.
 */
@Service
@ConditionalOnProperty(name = SearchConfig.BACKEND, havingValue = "elasticsearch")
public class ElasticsearchSearchIndex extends BackgroundSearchIndex {

  // Part of the index's name. Changing what is indexed, or how, means bumping it, so that an index
  // an earlier version made is made again rather than searched as if it were current.
  static final int FORMAT = 1;

  private static final String SETTING = "javadropbox.search.elasticsearch.";
  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);

  // A search has someone waiting for it, and holds one of the server's request threads: one that
  // Elasticsearch doesn't answer, waiting for a connection, connecting or reading, fails in
  // seconds,
  // and those after it fail at once for a few more, rather than letting a service that hangs tie
  // up every request thread, and with them uploads and downloads.
  private static final int SEARCH_TIMEOUT_MILLIS = 5000;
  private static final int SEARCH_CONNECT_MILLIS = 1000;
  static final Duration SEARCH_PAUSE = Duration.ofSeconds(5);
  // A search's requests, one after another, given up once they have taken this long in all: a
  // service slow to answer each, rather than silent, would otherwise hold the thread for minutes.
  private static final Duration SEARCH_DEADLINE = Duration.ofSeconds(10);
  // How long a failed search keeps the health check degraded, if nothing has searched since.
  private static final Duration SEARCH_PROBLEM_LASTS = Duration.ofMinutes(1);

  // What a search answers, with 503, while Elasticsearch can't; the log says why.
  static final String UNAVAILABLE =
      "Search can't be reached right now. Your files are fine. Please try again in a moment.";

  // A bulk request is sent once it holds this many items, or this much text.
  private static final int BULK_ITEMS = 500;
  private static final long BULK_CHARS = 5_000_000;
  // Items read back at a time, to compare their stamps with the store.
  static final int PAGE = 1000;
  // Changed items deleted by one request, and hits' texts fetched by one, for snippets: each text
  // can be up to 200,000 characters, so a search's are fetched, and cut, a few at a time.
  private static final int KEYS_PER_DELETE = 200;
  private static final int TEXTS_PER_FETCH = 10;

  // Failures that pass: too many requests, a node unavailable or the service behind a gateway.
  private static final Set<Integer> TRANSIENT = Set.of(429, 502, 503, 504);

  // What Elasticsearch takes as an index's name: lower case, without its separators and wildcards,
  // not starting with -, _ or +.
  private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,199}");

  private static final String PATH = "path";
  private static final String NAME = "name";
  private static final String CONTENT = "content";
  private static final String DIRECTORY = "directory";
  private static final String SIZE = "size";
  private static final String MODIFIED = "modified";
  private static final String ANALYZER = "javadropbox_text";

  private static final Logger log = LoggerFactory.getLogger(ElasticsearchSearchIndex.class);

  /** One item as it is stored. A folder has no text, size or time. */
  record Document(
      String path, String name, String content, boolean directory, Long size, Long modified) {}

  private final IndexableItems items;
  // What the app reads and writes through, and the index it points at.
  private final String alias;
  private final String index;
  private final String location;
  private final RestClient restClient;
  private final ElasticsearchClient client;
  // The same, with a search's short timeouts.
  private final ElasticsearchClient searchClient;

  // Whether the alias is known to point at this version's index. Written by the worker, and by a
  // search that finds the index gone.
  private volatile boolean ready;
  // Whether the index was made, and has yet to be filled with everything there is. Only the
  // worker touches it: it lasts until a batch that fills it has finished, retries and all.
  private boolean fill;
  // What has kept changes from being written, or a search from being answered, and been logged,
  // so that it isn't logged again every batch, or every search, until one gets through:
  // Elasticsearch out of reach, too busy, refusing the app's requests, or holding an index or alias
  // under the app's name that the app won't write to.
  private volatile String writeProblem;
  private volatile String searchProblem;
  // When a search last found Elasticsearch out of reach or too busy, if it did since one got
  // through.
  private volatile boolean searchPaused;
  private volatile long searchFailedAt;
  // When a search last failed, for how long searchProblem stands.
  private volatile long searchProblemAt;

  /**
   * @param url the service, such as {@code http://localhost:9200}; a path after it is a prefix of
   *     every request's, for a service behind a proxy
   * @param username with {@code password}, for a service that asks for them; empty for none
   * @param alias the name the index is known by; several apps sharing a service each need their own
   */
  ElasticsearchSearchIndex(
      IndexableItems items,
      @Value("${" + SETTING + "url:http://localhost:9200}") String url,
      @Value("${" + SETTING + "username:}") String username,
      @Value("${" + SETTING + "password:}") String password,
      @Value("${" + SETTING + "index:javadropbox}") String alias) {
    super(RETRY_DELAY, MAX_RETRY_DELAY);
    if (!NAME_PATTERN.matcher(alias).matches() || alias.equals(".") || alias.equals("..")) {
      throw new IllegalStateException(
          SETTING
              + "index must be lower case letters, digits, '.', '_' or '-', starting with a letter"
              + " or digit, not \""
              + alias
              + "\"");
    }
    this.items = items;
    this.alias = alias;
    this.index = alias + "-" + FORMAT;
    URI uri = parse(url);
    String prefix = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
    this.location = uri.getScheme() + "://" + uri.getAuthority() + prefix;
    RestClientBuilder builder =
        RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));
    if (!prefix.isEmpty()) {
      builder.setPathPrefix(prefix);
    }
    if (!username.isBlank()) {
      BasicCredentialsProvider credentials = new BasicCredentialsProvider();
      credentials.setCredentials(
          AuthScope.ANY, new UsernamePasswordCredentials(username, password));
      builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials));
    }
    this.restClient = builder.build();
    this.client =
        new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    RequestConfig searchTimeouts =
        RequestConfig.custom()
            .setConnectionRequestTimeout(SEARCH_CONNECT_MILLIS)
            .setConnectTimeout(SEARCH_CONNECT_MILLIS)
            .setSocketTimeout(SEARCH_TIMEOUT_MILLIS)
            .build();
    this.searchClient =
        client.withTransportOptions(
            new RestClientOptions(
                RequestOptions.DEFAULT.toBuilder().setRequestConfig(searchTimeouts).build(),
                false));
    log.info("Search uses the Elasticsearch index {} at {}", alias, location);
  }

  // Credentials go in their own settings, so that the URL can be logged as it is.
  static URI parse(String url) {
    URI uri;
    try {
      uri = URI.create(url.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(SETTING + "url is not a URL: " + e.getMessage());
    }
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())
        || uri.getHost() == null) {
      throw new IllegalStateException(
          SETTING + "url must be an http or https URL, such as http://localhost:9200");
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalStateException(
          SETTING
              + "url must not hold a username or password: set "
              + SETTING
              + "username and "
              + SETTING
              + "password instead");
    }
    return uri;
  }

  @Override
  Outcome write(boolean reconcileAll, List<String> changed) throws IOException {
    try {
      writeBatch(reconcileAll, changed);
      if (writeProblem != null) {
        writeProblem = null;
        log.info("Elasticsearch at {} takes the search index's changes again", location);
      }
      // It can be reached, so searches needn't wait for one to get through to say so.
      searchProblem = null;
      searchPaused = false;
      return Outcome.DONE;
    } catch (Unreachable e) {
      writeFailed("can't be reached (" + e.getCause() + "); changes wait and are tried again");
      return Outcome.RETRY;
    } catch (NotOurs e) {
      // Nothing to write to until someone renames or removes it.
      writeFailed("won't be written to: " + e.getMessage());
      return Outcome.DONE;
    } catch (IndexGone e) {
      log.warn("The search index {} has gone; making it again", index);
      ready = false;
      markBehind();
      // After the retry delay, not at once, in case something keeps deleting it.
      return Outcome.RETRY;
    } catch (ElasticsearchException e) {
      if (TRANSIENT.contains(e.status())) {
        writeFailed("is too busy (" + e.getMessage() + "); changes wait and are tried again");
        return Outcome.RETRY;
      }
      // Such as a refusal for want of a permission: the batch is given up, and the next reconcile
      // tries again.
      writeFailed("refused the search index's changes: " + e.getMessage());
      return Outcome.DONE;
    }
  }

  private void writeFailed(String what) {
    writeProblem = logOnce("Elasticsearch at " + location + " " + what, writeProblem);
  }

  // Logs the message as a warning, unless it is the one logged last time.
  private static String logOnce(String message, String last) {
    if (!message.equals(last)) {
      log.warn(message);
    } else {
      log.debug(message);
    }
    return message;
  }

  @Override
  public Optional<String> problem() {
    String problem = writeProblem;
    if (problem == null && System.nanoTime() - searchProblemAt < SEARCH_PROBLEM_LASTS.toNanos()) {
      problem = searchProblem;
    }
    return Optional.ofNullable(problem);
  }

  private void writeBatch(boolean reconcileAll, List<String> changed) throws IOException {
    if (prepare()) {
      fill = true;
    }
    // An index made afresh has to be filled with everything there is.
    boolean full = fill || reconcileAll;
    long started = System.nanoTime();
    Bulk bulk = new Bulk();
    if (full) {
      items.reconcile(indexedStamps(), this::closing, bulk::index, bulk::delete);
    }
    if (!changed.isEmpty() && !closing()) {
      // What this batch has written so far, made searchable, so that the deletion finds it too.
      bulk.flush();
      refresh();
      deleteUnder(changed, bulk);
      for (String key : changed) {
        if (closing()) {
          break;
        }
        items.walkUnder(key, this::closing, bulk::index);
      }
    }
    bulk.flush();
    if (bulk.indexed + bulk.removed > 0) {
      refresh();
    }
    if (full && !closing()) {
      fill = false;
      markCaughtUp();
    }

    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    String message = "Search index: {} items indexed and {} removed in {} ms";
    if (full && bulk.indexed + bulk.removed > 0) {
      log.info(message, bulk.indexed, bulk.removed, millis);
    } else {
      log.debug(message, bulk.indexed, bulk.removed, millis);
    }
  }

  /**
   * Makes this version's index and points the alias at it, if it doesn't already. Whether it did.
   * Once ready, only checks that the alias still points at it, which a newer version moving it
   * would change.
   */
  private boolean prepare() throws IOException {
    Set<String> current =
        call(() -> client.indices().existsAlias(e -> e.name(alias)).value())
            ? call(() -> client.indices().getAlias(g -> g.name(alias)).result().keySet())
            : Set.of();
    if (current.contains(index)) {
      if (!ready) {
        deleteOlder(current);
        ready = true;
      }
      return false;
    }
    if (ready) {
      ready = false;
      markBehind();
      if (current.isEmpty()) {
        throw new IndexGone();
      }
    }
    boolean made;
    if (current.size() == 1 && olderFormat(current.iterator().next())) {
      String old = current.iterator().next();
      log.info("Rebuilding the search index as {}, in place of {}", index, old);
      create();
      call(
          () ->
              client
                  .indices()
                  .updateAliases(
                      u ->
                          u.actions(a -> a.remove(r -> r.index(old).alias(alias)))
                              .actions(a -> a.add(ad -> ad.index(index).alias(alias)))));
      deleteOlder(Set.of(index));
      made = true;
    } else if (!current.isEmpty()) {
      throw new NotOurs(
          "the alias " + alias + " points at " + current + ", which this version didn't make");
    } else if (call(() -> client.indices().exists(e -> e.index(alias)).value())) {
      throw new NotOurs(
          "there is an index called "
              + alias
              + " that the app didn't make; set "
              + SETTING
              + "index to a name of its own");
    } else {
      log.info("Building the search index {}", index);
      create();
      try {
        // Pointing it at the index again, should another server have done so already, is fine.
        call(() -> client.indices().putAlias(p -> p.index(index).name(alias)));
      } catch (ElasticsearchException e) {
        if (e.status() == 400) {
          // An index under the alias's name appeared meanwhile.
          throw new NotOurs("an index called " + alias + " appeared that the app didn't make");
        }
        throw e;
      }
      made = true;
    }
    ready = true;
    return made;
  }

  // Older versions' indexes the alias no longer points at: what a change of format left, should
  // deleting them have failed, or another server have moved the alias at the same moment.
  private void deleteOlder(Set<String> current) throws IOException {
    Set<String> ours =
        call(() -> client.indices().get(g -> g.index(alias + "-*")).result().keySet());
    for (String other : ours) {
      if (!current.contains(other) && olderFormat(other)) {
        try {
          call(() -> client.indices().delete(d -> d.index(other)));
        } catch (ElasticsearchException e) {
          if (e.status() != 404) {
            throw e;
          }
        }
      }
    }
  }

  private boolean olderFormat(String other) {
    String suffix = other.startsWith(alias + "-") ? other.substring(alias.length() + 1) : "";
    return suffix.matches("[0-9]{1,9}") && Integer.parseInt(suffix) < FORMAT;
  }

  // Words as Unicode splits text into them, lower-cased and without accents, as in Lucene's index.
  // Another server may make it at the same moment, which is just as good.
  private void create() throws IOException {
    try {
      call(
          () ->
              client
                  .indices()
                  .create(
                      c ->
                          c.index(index)
                              .settings(
                                  s ->
                                      s.numberOfShards("1")
                                          // A replica wherever there is a second node to put it on.
                                          .autoExpandReplicas("0-1")
                                          .analysis(
                                              a ->
                                                  a.analyzer(
                                                      ANALYZER,
                                                      an ->
                                                          an.custom(
                                                              cu ->
                                                                  cu.tokenizer("standard")
                                                                      .filter(
                                                                          "lowercase",
                                                                          "asciifolding")))))
                              .mappings(
                                  m ->
                                      m.dynamic(DynamicMapping.Strict)
                                          .properties(PATH, p -> p.keyword(k -> k))
                                          .properties(NAME, p -> p.keyword(k -> k))
                                          .properties(
                                              CONTENT, p -> p.text(t -> t.analyzer(ANALYZER)))
                                          .properties(DIRECTORY, p -> p.boolean_(b -> b))
                                          .properties(SIZE, p -> p.long_(l -> l))
                                          .properties(MODIFIED, p -> p.long_(l -> l)))));
    } catch (ElasticsearchException e) {
      if (!"resource_already_exists_exception".equals(e.error().type())) {
        throw e;
      }
    }
  }

  // Every item's stamp, read a page at a time in order of path, from the values Elasticsearch
  // keeps beside each document rather than from the documents, whose text can be long.
  private Map<String, Stamp> indexedStamps() throws IOException {
    Map<String, Stamp> stamps = new HashMap<>();
    List<FieldValue> after = null;
    while (true) {
      List<FieldValue> from = after;
      SearchResponse<Void> page =
          search(
              client,
              s -> {
                s.index(alias)
                    .size(PAGE)
                    .trackTotalHits(t -> t.enabled(false))
                    .query(q -> q.matchAll(m -> m))
                    .sort(o -> o.field(f -> f.field(PATH).order(SortOrder.Asc)))
                    .source(src -> src.fetch(false))
                    .docvalueFields(f -> f.field(PATH))
                    .docvalueFields(f -> f.field(DIRECTORY))
                    .docvalueFields(f -> f.field(SIZE))
                    .docvalueFields(f -> f.field(MODIFIED));
                if (from != null) {
                  s.searchAfter(from);
                }
                return s;
              },
              Void.class);
      var hits = page.hits().hits();
      for (var hit : hits) {
        Map<String, JsonData> fields = hit.fields();
        String path = first(fields, PATH, String.class);
        Boolean directory = first(fields, DIRECTORY, Boolean.class);
        Long size = first(fields, SIZE, Long.class);
        Long modified = first(fields, MODIFIED, Long.class);
        stamps.put(
            path,
            Boolean.TRUE.equals(directory)
                ? Stamp.FOLDER
                : new Stamp(false, size != null ? size : -1, modified != null ? modified : -1));
      }
      if (hits.size() < PAGE) {
        return stamps;
      }
      after = hits.getLast().sort();
    }
  }

  private static <T> T first(Map<String, JsonData> fields, String field, Class<T> type) {
    JsonData values = fields.get(field);
    if (values == null) {
      return null;
    }
    List<?> list = values.to(List.class);
    if (list.isEmpty()) {
      return null;
    }
    Object value = list.getFirst();
    if (type == Long.class && value instanceof Number number) {
      return type.cast(number.longValue());
    }
    return type.cast(value);
  }

  // Drops everything at and below each changed key, a few hundred keys a request. What the walk
  // then finds is written by id, and made searchable at the end of the batch.
  private void deleteUnder(List<String> keys, Bulk bulk) throws IOException {
    for (int from = 0; from < keys.size(); from += KEYS_PER_DELETE) {
      List<String> some = keys.subList(from, Math.min(keys.size(), from + KEYS_PER_DELETE));
      BoolQuery.Builder any = new BoolQuery.Builder();
      for (String key : some) {
        if (key.isEmpty()) {
          any.should(s -> s.matchAll(m -> m));
        } else {
          any.should(s -> s.term(t -> t.field(PATH).value(key)));
          any.should(s -> s.prefix(p -> p.field(PATH).value(key + "/")));
        }
      }
      DeleteByQueryResponse response =
          gone(
              () ->
                  client.deleteByQuery(
                      d ->
                          d.index(alias)
                              .query(q -> q.bool(any.build()))
                              .conflicts(Conflicts.Proceed)));
      if (!response.failures().isEmpty()) {
        throw new IOException(
            "Elasticsearch could not delete some search items: "
                + response.failures().getFirst().cause().reason());
      }
      bulk.removed += response.deleted() != null ? response.deleted().intValue() : 0;
    }
  }

  private void refresh() throws IOException {
    gone(() -> client.indices().refresh(r -> r.index(alias)));
  }

  private static <T> SearchResponse<T> search(
      ElasticsearchClient via,
      Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> request,
      Class<T> type)
      throws Unreachable {
    return gone(() -> via.search(request, type));
  }

  @Override
  public Hits search(Home home, String folderKey, String text, int limit) throws IOException {
    if (!ready) {
      if (writeProblem != null) {
        // Logged when it happened.
        throw new ServiceUnavailableException(UNAVAILABLE);
      }
      // The index is still being made.
      return new Hits(List.of(), 0, false);
    }
    if (searchPaused && System.nanoTime() - searchFailedAt < SEARCH_PAUSE.toNanos()) {
      throw new ServiceUnavailableException(UNAVAILABLE);
    }
    try {
      Hits hits = find(home, folderKey, text, limit);
      searchPaused = false;
      if (searchProblem != null) {
        searchProblem = null;
        log.info("Elasticsearch at {} answers searches again", location);
      }
      return hits;
    } catch (IndexGone e) {
      ready = false;
      markBehind();
      reconcile();
      return new Hits(List.of(), 0, false);
    } catch (Unreachable e) {
      pauseSearches();
      throw searchFailed("can't be reached (" + e.getCause() + ")");
    } catch (ElasticsearchException e) {
      if (TRANSIENT.contains(e.status())) {
        pauseSearches();
        throw searchFailed("is too busy (" + e.getMessage() + ")");
      }
      throw searchFailed("refused a search: " + e.getMessage());
    }
  }

  private void pauseSearches() {
    searchFailedAt = System.nanoTime();
    searchPaused = true;
  }

  private ServiceUnavailableException searchFailed(String what) {
    searchProblem =
        logOnce("Elasticsearch at " + location + " " + what + "; searches fail", searchProblem);
    searchProblemAt = System.nanoTime();
    return new ServiceUnavailableException(UNAVAILABLE);
  }

  private Hits find(Home home, String folderKey, String text, int limit) throws IOException {
    long deadline = System.nanoTime() + SEARCH_DEADLINE.toNanos();
    String homeKey = home.indexKey("");
    SearchResponse<Void> response =
        search(
            searchClient,
            s ->
                s.index(alias)
                    .size(limit)
                    .trackTotalHits(t -> t.enabled(true))
                    .query(query(home.indexKey(folderKey), text))
                    .source(src -> src.fetch(false))
                    .docvalueFields(f -> f.field(PATH)),
            Void.class);
    List<String> paths = new ArrayList<>();
    for (var hit : response.hits().hits()) {
      paths.add(first(hit.fields(), PATH, String.class));
    }
    Map<String, Snippet> snippets = snippets(paths, text, deadline);
    List<SearchIndex.Hit> hits = new ArrayList<>();
    for (String path : paths) {
      hits.add(new SearchIndex.Hit(path.substring(homeKey.length() + 1), snippets.get(path)));
    }
    long total = response.hits().total() != null ? response.hits().total().value() : hits.size();
    return new Hits(hits, total, isCaughtUp());
  }

  // Each hit's snippet, cut from its text a few hits at a time, so that no more than a few texts
  // are held at once. The query is Lucene's, without the folder: the highlighter, given only the
  // text, would find nothing that matches a filter on the path.
  private Map<String, Snippet> snippets(List<String> paths, String text, long deadline)
      throws IOException {
    org.apache.lucene.search.Query highlight = LuceneSearchIndex.query("", text);
    Map<String, Snippet> snippets = new HashMap<>();
    for (int from = 0; from < paths.size(); from += TEXTS_PER_FETCH) {
      if (System.nanoTime() - deadline > 0) {
        throw new Unreachable(
            new SocketTimeoutException("no answer to a search within " + SEARCH_DEADLINE));
      }
      List<String> ids =
          paths.subList(from, Math.min(paths.size(), from + TEXTS_PER_FETCH)).stream()
              .map(ElasticsearchSearchIndex::id)
              .toList();
      MgetResponse<Document> response =
          gone(
              () ->
                  searchClient.mget(
                      m -> m.index(alias).ids(ids).sourceIncludes(PATH, CONTENT), Document.class));
      for (MultiGetResponseItem<Document> item : response.docs()) {
        if (item.isResult() && item.result().found() && item.result().source() != null) {
          Document document = item.result().source();
          if (document.content() != null) {
            snippets.put(document.path(), LuceneSearchIndex.snippet(highlight, document.content()));
          }
        }
      }
    }
    return snippets;
  }

  /**
   * {@link LuceneSearchIndex#query} in Elasticsearch's terms: every word has to be in the name, as
   * a substring of the folded name, or in the text, as a word, or a phrase when it is several; the
   * last word also as the start of a longer one. All the words together in the text rank higher.
   */
  static Query query(String folderKey, String text) {
    String[] words = text.strip().split("\\s+");
    BoolQuery.Builder query = new BoolQuery.Builder();
    List<String> allTokens = new ArrayList<>();
    for (int i = 0; i < words.length; i++) {
      List<String> tokens = LuceneSearchIndex.tokens(words[i]);
      allTokens.addAll(tokens);
      query.must(word(words[i], tokens, i == words.length - 1));
    }
    if (words.length > 1 && allTokens.size() > 1) {
      query.should(
          s ->
              s.matchPhrase(
                  p ->
                      p.field(CONTENT)
                          .query(String.join(" ", allTokens))
                          .boost(LuceneSearchIndex.PHRASE_BOOST)));
    }
    if (!folderKey.isEmpty()) {
      query.filter(f -> f.prefix(p -> p.field(PATH).value(folderKey + "/")));
    }
    return Query.of(q -> q.bool(query.build()));
  }

  private static Query word(String word, List<String> tokens, boolean last) {
    BoolQuery.Builder either = new BoolQuery.Builder();
    String inName = "*" + LuceneSearchIndex.escapeWildcards(LuceneSearchIndex.fold(word)) + "*";
    either.should(
        s -> s.wildcard(w -> w.field(NAME).value(inName).boost(LuceneSearchIndex.NAME_BOOST)));
    if (tokens.size() == 1) {
      String token = tokens.getFirst();
      either.should(s -> s.term(t -> t.field(CONTENT).value(token)));
      if (last && token.length() >= LuceneSearchIndex.MIN_PREFIX_LENGTH) {
        either.should(
            s ->
                s.prefix(p -> p.field(CONTENT).value(token).boost(LuceneSearchIndex.PREFIX_BOOST)));
      }
    } else if (tokens.size() > 1) {
      // One word to whoever typed it, such as "e-mail" or "v2.1", but several to the analyser.
      either.should(s -> s.matchPhrase(p -> p.field(CONTENT).query(String.join(" ", tokens))));
    }
    return Query.of(q -> q.bool(either.build()));
  }

  @Override
  void closeIndex() throws IOException {
    restClient.close();
  }

  // Items are stored under a hash of their key: a path can be longer than an id may be. Changing
  // how means changing the format.
  private static String id(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private interface Call<T> {
    T run() throws IOException;
  }

  // A request to Elasticsearch, whose IOExceptions are its connection's: refused, reset or timed
  // out. Told apart from the store's, which give a batch up rather than having it tried again.
  private static <T> T call(Call<T> request) throws Unreachable {
    try {
      return request.run();
    } catch (IOException e) {
      throw new Unreachable(e);
    } catch (ElasticsearchException e) {
      throw e;
    } catch (RuntimeException e) {
      // The low-level client wraps a wait for a free connection that timed out, among others, in
      // a plain RuntimeException.
      if (e.getCause() instanceof IOException || e.getCause() instanceof TimeoutException) {
        throw new Unreachable(new IOException(e.getCause().toString(), e.getCause()));
      }
      throw e;
    }
  }

  // Like call, for a request on the index, which not being found means it went behind the app's
  // back.
  private static <T> T gone(Call<T> request) throws Unreachable {
    try {
      return call(request);
    } catch (ElasticsearchException e) {
      if (e.status() == 404) {
        throw new IndexGone();
      }
      throw e;
    }
  }

  /** Elasticsearch could not be reached. */
  private static final class Unreachable extends IOException {
    Unreachable(IOException cause) {
      super(cause.getMessage(), cause);
    }
  }

  /** The alias or the index's name is taken by something the app won't write to. */
  private static final class NotOurs extends IOException {
    NotOurs(String message) {
      super(message);
    }
  }

  /** The index went behind the app's back, and has to be made again. */
  private static final class IndexGone extends RuntimeException {
    IndexGone() {
      super(null, null, false, false);
    }
  }

  /** Writes, sent in bulk requests through the alias, and counted for the log. */
  private final class Bulk {

    private BulkRequest.Builder request = new BulkRequest.Builder().requireAlias(true);
    private int pending;
    private long chars;
    int indexed;
    int removed;

    void index(String key, Item entry) throws IOException {
      Document document =
          entry.isDirectory()
              ? new Document(key, LuceneSearchIndex.fold(entry.name()), null, true, null, null)
              : new Document(
                  key,
                  LuceneSearchIndex.fold(entry.name()),
                  items.text(key, entry).orElse(null),
                  false,
                  entry.size(),
                  Stamp.millis(entry));
      request.operations(o -> o.index(i -> i.index(alias).id(id(key)).document(document)));
      indexed++;
      chars += key.length() + (document.content() != null ? document.content().length() : 0);
      pendingOne();
    }

    void delete(String key) throws IOException {
      request.operations(o -> o.delete(d -> d.index(alias).id(id(key))));
      removed++;
      pendingOne();
    }

    private void pendingOne() throws IOException {
      pending++;
      if (pending >= BULK_ITEMS || chars >= BULK_CHARS) {
        flush();
      }
    }

    void flush() throws IOException {
      if (pending == 0) {
        return;
      }
      BulkRequest sent = request.build();
      request = new BulkRequest.Builder().requireAlias(true);
      pending = 0;
      chars = 0;
      BulkResponse response = gone(() -> client.bulk(sent));
      if (!response.errors()) {
        return;
      }
      // A deletion of something already gone comes back without an error.
      for (BulkResponseItem item : response.items()) {
        if (item.error() == null) {
          continue;
        }
        if ("index_not_found_exception".equals(item.error().type())) {
          throw new IndexGone();
        }
        if (TRANSIENT.contains(item.status())) {
          throw new Unreachable(new IOException("too busy: " + item.error().reason()));
        }
        // One item refused, which trying again won't change, mustn't stop the rest: it is left
        // out, and the next reconcile tries it again.
        log.warn("Elasticsearch refused a search item: {}", item.error().reason());
      }
    }
  }
}
