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
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.javadropbox.javadropbox.config.SearchConfig;
import com.javadropbox.javadropbox.dto.Snippet;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.IndexableItems.Stamp;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
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
 * out exactly as Lucene's own do.
 *
 * <p>Each item's size and modification time are kept with it, so a reconcile reads again only the
 * files that differ. The index is made when it is first written to, and made again, then filled
 * with everything there is, when an earlier version of the app made it differently.
 */
@Service
@ConditionalOnProperty(name = SearchConfig.BACKEND, havingValue = "elasticsearch")
public class ElasticsearchSearchIndex extends BackgroundSearchIndex {

  // Recorded in the index's mapping. Changing what is indexed, or how, means bumping it, so that
  // an index an earlier version made is made again rather than searched as if it were current.
  private static final String FORMAT = "1";
  private static final String FORMAT_KEY = "javadropbox_format";

  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

  // A bulk request is sent once it holds this many items, or this much text.
  private static final int BULK_ITEMS = 500;
  private static final long BULK_CHARS = 5_000_000;
  // Items read back at a time, to compare their stamps with the store.
  private static final int PAGE = 1000;

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
  private final String index;
  private final RestClient restClient;
  private final ElasticsearchClient client;

  // Whether the index is known to exist in this version's form. Only the worker writes it.
  private volatile boolean ready;

  /**
   * @param url the service, such as {@code http://localhost:9200}
   * @param username with {@code password}, for a service that asks for them; empty for none
   * @param index the name of the index, which several apps on one service would each need their own
   *     of
   */
  ElasticsearchSearchIndex(
      IndexableItems items,
      @Value("${javadropbox.search.elasticsearch.url:http://localhost:9200}") String url,
      @Value("${javadropbox.search.elasticsearch.username:}") String username,
      @Value("${javadropbox.search.elasticsearch.password:}") String password,
      @Value("${javadropbox.search.elasticsearch.index:javadropbox}") String index) {
    super(RETRY_DELAY);
    this.items = items;
    this.index = index;
    RestClientBuilder builder = RestClient.builder(HttpHost.create(url));
    if (!username.isBlank()) {
      BasicCredentialsProvider credentials = new BasicCredentialsProvider();
      credentials.setCredentials(
          AuthScope.ANY, new UsernamePasswordCredentials(username, password));
      builder.setHttpClientConfigCallback(http -> http.setDefaultCredentialsProvider(credentials));
    }
    this.restClient = builder.build();
    this.client =
        new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    log.info("Search uses the Elasticsearch index {} at {}", index, url);
  }

  @Override
  Outcome write(boolean reconcileAll, List<String> changed) throws IOException {
    // An index made afresh has to be filled with everything there is.
    boolean full = prepare() || reconcileAll;
    long started = System.nanoTime();
    Bulk bulk = new Bulk();
    if (full) {
      items.reconcile(indexedStamps(), this::closing, bulk::index, bulk::delete);
    }
    for (String key : changed) {
      if (closing()) {
        break;
      }
      replace(key, bulk);
    }
    bulk.flush();
    refresh();
    if (full && !closing()) {
      markCaughtUp();
    }

    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    String message = "Search index: {} items indexed and {} removed in {} ms";
    if (full && bulk.indexed + bulk.removed > 0) {
      log.info(message, bulk.indexed, bulk.removed, millis);
    } else {
      log.debug(message, bulk.indexed, bulk.removed, millis);
    }
    return Outcome.DONE;
  }

  // Makes the index if it isn't there, or is there in an earlier version's form. Whether it did.
  private boolean prepare() throws IOException {
    if (ready) {
      return false;
    }
    boolean made = false;
    if (client.indices().exists(e -> e.index(index)).value()) {
      JsonData format =
          client
              .indices()
              .getMapping(g -> g.index(index))
              .get(index)
              .mappings()
              .meta()
              .get(FORMAT_KEY);
      if (format == null || !FORMAT.equals(format.to(String.class))) {
        log.info("Rebuilding the search index {}, which another version made", index);
        client.indices().delete(d -> d.index(index));
        create();
        made = true;
      }
    } else {
      log.info("Building the search index {}", index);
      create();
      made = true;
    }
    ready = true;
    return made;
  }

  // Words as Unicode splits text into them, lower-cased and without accents, as in Lucene's index.
  private void create() throws IOException {
    client
        .indices()
        .create(
            c ->
                c.index(index)
                    .settings(
                        s ->
                            s.numberOfShards("1")
                                .analysis(
                                    a ->
                                        a.analyzer(
                                            ANALYZER,
                                            an ->
                                                an.custom(
                                                    cu ->
                                                        cu.tokenizer("standard")
                                                            .filter("lowercase", "asciifolding")))))
                    .mappings(
                        m ->
                            m.dynamic(DynamicMapping.Strict)
                                .meta(FORMAT_KEY, JsonData.of(FORMAT))
                                .properties(PATH, p -> p.keyword(k -> k))
                                .properties(NAME, p -> p.keyword(k -> k))
                                .properties(CONTENT, p -> p.text(t -> t.analyzer(ANALYZER)))
                                .properties(DIRECTORY, p -> p.boolean_(b -> b))
                                .properties(SIZE, p -> p.long_(l -> l))
                                .properties(MODIFIED, p -> p.long_(l -> l))));
  }

  // Every item's stamp, read a page at a time in order of path.
  private Map<String, Stamp> indexedStamps() throws IOException {
    Map<String, Stamp> stamps = new java.util.HashMap<>();
    String after = null;
    while (true) {
      String from = after;
      SearchResponse<Document> page =
          client.search(
              s -> {
                s.index(index)
                    .size(PAGE)
                    .query(q -> q.matchAll(m -> m))
                    .sort(o -> o.field(f -> f.field(PATH).order(SortOrder.Asc)))
                    .source(src -> src.filter(f -> f.includes(PATH, DIRECTORY, SIZE, MODIFIED)));
                if (from != null) {
                  s.searchAfter(FieldValue.of(from));
                }
                return s;
              },
              Document.class);
      List<co.elastic.clients.elasticsearch.core.search.Hit<Document>> hits = page.hits().hits();
      for (co.elastic.clients.elasticsearch.core.search.Hit<Document> hit : hits) {
        Document document = hit.source();
        stamps.put(
            document.path(),
            document.directory()
                ? Stamp.FOLDER
                : new Stamp(
                    false,
                    document.size() != null ? document.size() : -1,
                    document.modified() != null ? document.modified() : -1));
      }
      if (hits.size() < PAGE) {
        return stamps;
      }
      after = hits.getLast().source().path();
    }
  }

  // Drops everything at and below key, and indexes what is stored there now. What this batch has
  // written so far is sent and made searchable first, so the deletion finds it too.
  private void replace(String key, Bulk bulk) throws IOException {
    bulk.flush();
    refresh();
    Query what =
        key.isEmpty()
            ? Query.of(q -> q.matchAll(m -> m))
            : Query.of(
                q ->
                    q.bool(
                        b ->
                            b.should(s -> s.term(t -> t.field(PATH).value(key)))
                                .should(s -> s.prefix(p -> p.field(PATH).value(key + "/")))));
    long deleted =
        client
            .deleteByQuery(
                d -> d.index(index).query(what).conflicts(Conflicts.Proceed).refresh(true))
            .deleted();
    bulk.removed += (int) deleted;
    items.walkUnder(key, this::closing, bulk::index);
  }

  private void refresh() throws IOException {
    client.indices().refresh(r -> r.index(index));
  }

  @Override
  public Hits search(Home home, String folderKey, String text, int limit) throws IOException {
    if (!ready) {
      return new Hits(List.of(), 0, false);
    }
    String homeKey = home.indexKey("");
    String folder = home.indexKey(folderKey);
    SearchResponse<Document> response;
    try {
      response =
          client.search(
              s ->
                  s.index(index)
                      .size(limit)
                      .trackTotalHits(t -> t.enabled(true))
                      .query(query(folder, text))
                      .source(src -> src.filter(f -> f.includes(PATH, CONTENT))),
              Document.class);
    } catch (ElasticsearchException e) {
      if (e.status() == 404) {
        // Deleted behind the app's back; the next reconcile makes it again.
        ready = false;
        return new Hits(List.of(), 0, false);
      }
      throw e;
    }
    // The same query as Lucene's, to cut each hit's snippet from its text. Without the folder: the
    // highlighter, given only the text, would find nothing that matches a filter on the path.
    org.apache.lucene.search.Query highlight = LuceneSearchIndex.query("", text);
    List<SearchIndex.Hit> hits = new ArrayList<>();
    for (co.elastic.clients.elasticsearch.core.search.Hit<Document> hit : response.hits().hits()) {
      Document document = hit.source();
      Snippet snippet =
          document.content() == null
              ? null
              : LuceneSearchIndex.snippet(highlight, document.content());
      hits.add(new SearchIndex.Hit(document.path().substring(homeKey.length() + 1), snippet));
    }
    long total = response.hits().total() != null ? response.hits().total().value() : hits.size();
    return new Hits(hits, total, isCaughtUp());
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

  // Items are stored under a hash of their key: a path can be longer than an id may be.
  private static String id(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Writes, sent in bulk requests, and counted for the log. */
  private final class Bulk {

    private BulkRequest.Builder request = new BulkRequest.Builder();
    private int pending;
    private long chars;
    int indexed;
    int removed;

    void index(String key, Entry entry) throws IOException {
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
      request.operations(o -> o.index(i -> i.index(index).id(id(key)).document(document)));
      indexed++;
      chars += key.length() + (document.content() != null ? document.content().length() : 0);
      pendingOne();
    }

    void delete(String key) throws IOException {
      request.operations(o -> o.delete(d -> d.index(index).id(id(key))));
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
      BulkResponse response = client.bulk(request.build());
      request = new BulkRequest.Builder();
      pending = 0;
      chars = 0;
      if (response.errors()) {
        for (BulkResponseItem item : response.items()) {
          // An item that was already gone needs no deleting.
          if (item.error() != null && item.status() != 404) {
            throw new IOException("Elasticsearch refused an item: " + item.error().reason());
          }
        }
      }
    }
  }
}
