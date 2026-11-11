package com.javadropbox.javadropbox.service;

import com.javadropbox.javadropbox.config.SearchConfig;
import com.javadropbox.javadropbox.dto.Snippet;
import com.javadropbox.javadropbox.service.FileStore.Entry;
import com.javadropbox.javadropbox.service.IndexableItems.Stamp;
import com.javadropbox.javadropbox.service.StoragePaths.Home;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.uhighlight.LengthGoalBreakIterator;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The {@link SearchIndex} as a Lucene index inside the app. Each item is indexed under its
 * account's id followed by its path. The index can be deleted at any time to be rebuilt from what
 * is stored.
 *
 * <p>It is written on {@link BackgroundSearchIndex}'s background thread. Each item's size and
 * modification time are kept with it, so a reconcile reads again only the files that differ.
 *
 * <p>It lives on the local disk, in {@code .javadropbox/search-index} in the serving directory or
 * wherever {@code javadropbox.search.index-directory} says, whichever store holds the files. The
 * writer is open only while there is work, so an idle server holds no lock on it.
 */
@Service
@ConditionalOnProperty(name = SearchConfig.BACKEND, havingValue = "lucene", matchIfMissing = true)
public class LuceneSearchIndex extends BackgroundSearchIndex {

  // Recorded with every commit. Changing what is indexed, or how, means bumping it, so that an
  // index an earlier version wrote is rebuilt rather than searched as if it were current. 2: keys
  // start with the account's id.
  private static final String FORMAT = "2";
  private static final String FORMAT_KEY = "javadropbox.search.format";

  // Before trying again when another writer, such as a second server on the same storage, holds
  // the index's lock.
  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

  // Small, for the demo's small heap: added documents go to disk sooner.
  private static final double RAM_BUFFER_MB = 8;

  /** The item's key: matched exactly, returned with hits, and read back by a reconcile. */
  private static final String PATH = "path";

  /** The whole name, lower-cased and without accents, matched as a substring. */
  private static final String NAME = "name";

  /** The text, split into words; also stored, for snippets. */
  private static final String CONTENT = "content";

  private static final String DIRECTORY = "directory";
  private static final String SIZE = "size";
  private static final String MODIFIED = "modified";

  // Positions for phrases, and offsets so that snippets are cut from the stored text without
  // analysing it again.
  private static final FieldType CONTENT_FIELD = new FieldType(TextField.TYPE_STORED);

  static {
    CONTENT_FIELD.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    CONTENT_FIELD.freeze();
  }

  /**
   * Words as Unicode splits text into them, lower-cased and without accents, so that "cafe" finds
   * "Café" and "causevic" finds "Čaušević". There is no stemming, which would favour one language.
   */
  static final Analyzer ANALYZER =
      new Analyzer() {
        @Override
        protected TokenStreamComponents createComponents(String field) {
          Tokenizer words = new StandardTokenizer();
          return new TokenStreamComponents(
              words, new ASCIIFoldingFilter(new LowerCaseFilter(words)));
        }

        @Override
        protected TokenStream normalize(String field, TokenStream in) {
          return new ASCIIFoldingFilter(new LowerCaseFilter(in));
        }
      };

  // A word in the name puts an item ahead of any whose text alone matches, so that what is named
  // after the search comes first; relevance in the text orders the rest. In the text, a whole word
  // counts for more than the start of a longer one, and the words together in order for more than
  // the same words apart.
  static final float NAME_BOOST = 1000;
  static final float PREFIX_BOOST = 0.5f;
  static final float PHRASE_BOOST = 2;
  // Shorter starts of words match too much of the text to be worth showing.
  static final int MIN_PREFIX_LENGTH = 3;

  private static final int SNIPPET_LENGTH = 160;
  private static final String ELLIPSIS = "…";

  private static final Logger log = LoggerFactory.getLogger(LuceneSearchIndex.class);

  private final IndexableItems items;
  private final Path location;
  private final Directory directory;

  // Null while there is no index to search yet.
  private volatile SearcherManager searchers;

  // Whether the next batch starts the index over. Only the worker touches it once it is running.
  private boolean rebuild;

  LuceneSearchIndex(
      StoragePaths storagePaths,
      IndexableItems items,
      @Value("${javadropbox.search.index-directory:}") String indexDirectory)
      throws IOException {
    super(RETRY_DELAY);
    this.items = items;
    this.location =
        indexDirectory.isBlank()
            ? storagePaths.internalDir().resolve("search-index")
            : Path.of(indexDirectory).toAbsolutePath().normalize();
    // Plain reads rather than the memory mapping FSDirectory.open would pick: the index is small,
    // and mapping it calls native code, which the JVM warns about at every start.
    this.directory = new NIOFSDirectory(location);
    this.searchers = openSearchers();
  }

  // An index another version of the app wrote, or one that cannot be read, is started over.
  private SearcherManager openSearchers() {
    try {
      if (!DirectoryReader.indexExists(directory)) {
        log.info("Building the search index in {}", location);
        rebuild = true;
        return null;
      }
      SearcherManager manager = new SearcherManager(directory, null);
      IndexSearcher searcher = manager.acquire();
      String format;
      try {
        format =
            ((DirectoryReader) searcher.getIndexReader())
                .getIndexCommit()
                .getUserData()
                .get(FORMAT_KEY);
      } finally {
        manager.release(searcher);
      }
      if (FORMAT.equals(format)) {
        return manager;
      }
      manager.close();
      log.info("Rebuilding the search index in {}, which another version wrote", location);
    } catch (IOException e) {
      log.warn("Could not open the search index in {}; rebuilding it", location, e);
    }
    rebuild = true;
    return null;
  }

  // A batch the index's lock or a damaged index kept from being written is tried again; a
  // damaged index is started over first.
  @Override
  Outcome write(boolean reconcileAll, List<String> changed) throws IOException {
    try {
      writeBatch(reconcileAll, changed);
      return Outcome.DONE;
    } catch (LockObtainFailedException e) {
      log.debug("The search index is locked by another writer; trying again in {}", RETRY_DELAY);
      return Outcome.RETRY;
    } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException e) {
      log.warn("Could not read the search index in {}; rebuilding it", location, e);
      rebuild = true;
      return Outcome.RETRY;
    }
  }

  private void writeBatch(boolean reconcileAll, List<String> changed) throws IOException {
    // An index started over has to be filled with everything there is.
    boolean full = reconcileAll || rebuild;
    long started = System.nanoTime();
    Counts counts = new Counts();
    if (rebuild) {
      closeSearchers();
      clear();
    }
    IndexWriterConfig config =
        new IndexWriterConfig(ANALYZER)
            .setOpenMode(rebuild ? OpenMode.CREATE : OpenMode.CREATE_OR_APPEND)
            .setRAMBufferSizeMB(RAM_BUFFER_MB);
    try (IndexWriter writer = new IndexWriter(directory, config)) {
      if (full) {
        reconcileAll(writer, counts);
      }
      for (String key : changed) {
        if (closing()) {
          break;
        }
        replace(writer, key, counts);
      }
      writer.setLiveCommitData(Map.of(FORMAT_KEY, FORMAT).entrySet(), false);
      writer.commit();
    }
    rebuild = false;
    refreshSearchers();
    if (full && !closing()) {
      markCaughtUp();
    }

    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    String message = "Search index: {} items indexed and {} removed in {} ms";
    if (full && counts.indexed + counts.removed > 0) {
      log.info(message, counts.indexed, counts.removed, millis);
    } else {
      log.debug(message, counts.indexed, counts.removed, millis);
    }
  }

  // Even a writer told to start over reads the last commit first, so an index it cannot read has
  // to go before one can open. Under the write lock, in case another writer is at it.
  private void clear() throws IOException {
    try (Lock writing = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME)) {
      for (String file : directory.listAll()) {
        if (!file.equals(IndexWriter.WRITE_LOCK_NAME)) {
          directory.deleteFile(file);
        }
      }
    }
  }

  // Searches find nothing, and say the index is catching up, until it has been rebuilt.
  private void closeSearchers() throws IOException {
    SearcherManager manager = searchers;
    searchers = null;
    if (manager != null) {
      manager.close();
    }
  }

  private void refreshSearchers() throws IOException {
    SearcherManager manager = searchers;
    if (manager == null) {
      searchers = new SearcherManager(directory, null);
    } else {
      manager.maybeRefreshBlocking();
    }
  }

  // Compares what is indexed with what is stored, item by item, and indexes what differs.
  private void reconcileAll(IndexWriter writer, Counts counts) throws IOException {
    items.reconcile(
        indexedStamps(writer),
        this::closing,
        (key, entry) -> {
          writer.updateDocument(new Term(PATH, key), document(key, entry));
          counts.indexed++;
        },
        gone -> {
          writer.deleteDocuments(new Term(PATH, gone));
          counts.removed++;
        });
  }

  // Includes what this batch has written so far, which is not committed yet.
  private static Map<String, Stamp> indexedStamps(IndexWriter writer) throws IOException {
    Map<String, Stamp> stamps = new HashMap<>();
    try (DirectoryReader reader = DirectoryReader.open(writer)) {
      for (LeafReaderContext leaf : reader.leaves()) {
        LeafReader segment = leaf.reader();
        Bits live = segment.getLiveDocs();
        SortedDocValues paths = DocValues.getSorted(segment, PATH);
        NumericDocValues directories = DocValues.getNumeric(segment, DIRECTORY);
        NumericDocValues sizes = DocValues.getNumeric(segment, SIZE);
        NumericDocValues modified = DocValues.getNumeric(segment, MODIFIED);
        for (int doc = paths.nextDoc();
            doc != DocIdSetIterator.NO_MORE_DOCS;
            doc = paths.nextDoc()) {
          if (live != null && !live.get(doc)) {
            continue;
          }
          String key = paths.lookupOrd(paths.ordValue()).utf8ToString();
          boolean isDirectory = directories.advanceExact(doc) && directories.longValue() == 1;
          stamps.put(
              key,
              isDirectory
                  ? Stamp.FOLDER
                  : new Stamp(
                      false,
                      sizes.advanceExact(doc) ? sizes.longValue() : -1,
                      modified.advanceExact(doc) ? modified.longValue() : -1));
        }
      }
    }
    return stamps;
  }

  // Drops everything at and below key, and indexes what is stored there now.
  private void replace(IndexWriter writer, String key, Counts counts) throws IOException {
    if (key.isEmpty()) {
      writer.deleteAll();
    } else {
      writer.deleteDocuments(
          new TermQuery(new Term(PATH, key)), new PrefixQuery(new Term(PATH, key + "/")));
    }
    items.walkUnder(
        key,
        this::closing,
        (itemKey, entry) -> {
          writer.addDocument(document(itemKey, entry));
          counts.indexed++;
        });
  }

  private Document document(String key, Entry entry) {
    String name = entry.name();
    Document document = new Document();
    document.add(new StringField(PATH, key, Field.Store.YES));
    document.add(new SortedDocValuesField(PATH, new BytesRef(key)));
    document.add(new StringField(NAME, fold(name), Field.Store.NO));
    document.add(new NumericDocValuesField(DIRECTORY, entry.isDirectory() ? 1 : 0));
    if (!entry.isDirectory()) {
      document.add(new NumericDocValuesField(SIZE, entry.size()));
      document.add(new NumericDocValuesField(MODIFIED, Stamp.millis(entry)));
      items
          .text(key, entry)
          .ifPresent(text -> document.add(new Field(CONTENT, text, CONTENT_FIELD)));
    }
    return document;
  }

  @Override
  public Hits search(Home home, String folderKey, String text, int limit) throws IOException {
    SearcherManager manager = searchers;
    IndexSearcher searcher;
    try {
      searcher = manager == null ? null : manager.acquire();
    } catch (AlreadyClosedException e) {
      // Closed for a rebuild while this search began.
      searcher = null;
    }
    if (searcher == null) {
      return new Hits(List.of(), 0, false);
    }
    try {
      String homeKey = home.indexKey("");
      Query query = query(home.indexKey(folderKey), text);
      TopDocs top =
          searcher.search(query, new TopScoreDocCollectorManager(limit, Integer.MAX_VALUE));
      int[] docIds = Arrays.stream(top.scoreDocs).mapToInt(scoreDoc -> scoreDoc.doc).toArray();
      Object[] snippets =
          docIds.length == 0
              ? new Object[0]
              : new SnippetHighlighter(searcher).snippets(query, docIds);
      StoredFields fields = searcher.storedFields();
      List<Hit> hits = new ArrayList<>(docIds.length);
      for (int i = 0; i < docIds.length; i++) {
        String path = fields.document(docIds[i], Set.of(PATH)).get(PATH);
        hits.add(new Hit(path.substring(homeKey.length() + 1), (Snippet) snippets[i]));
      }
      return new Hits(hits, top.totalHits.value(), isCaughtUp());
    } finally {
      manager.release(searcher);
    }
  }

  // The last word also matches as the start of a longer one, so that results come up while it is
  // still being typed.
  static Query query(String folderKey, String text) {
    String[] words = text.strip().split("\\s+");
    BooleanQuery.Builder query = new BooleanQuery.Builder();
    List<String> allTokens = new ArrayList<>();
    for (int i = 0; i < words.length; i++) {
      List<String> tokens = tokens(words[i]);
      allTokens.addAll(tokens);
      query.add(word(words[i], tokens, i == words.length - 1), Occur.MUST);
    }
    if (words.length > 1 && allTokens.size() > 1) {
      query.add(
          new BoostQuery(new PhraseQuery(CONTENT, allTokens.toArray(String[]::new)), PHRASE_BOOST),
          Occur.SHOULD);
    }
    if (!folderKey.isEmpty()) {
      query.add(new PrefixQuery(new Term(PATH, folderKey + "/")), Occur.FILTER);
    }
    return query.build();
  }

  private static Query word(String word, List<String> tokens, boolean last) {
    BooleanQuery.Builder either = new BooleanQuery.Builder();
    String inName = "*" + escapeWildcards(fold(word)) + "*";
    either.add(new BoostQuery(new WildcardQuery(new Term(NAME, inName)), NAME_BOOST), Occur.SHOULD);
    if (tokens.size() == 1) {
      String token = tokens.getFirst();
      either.add(new TermQuery(new Term(CONTENT, token)), Occur.SHOULD);
      if (last && token.length() >= MIN_PREFIX_LENGTH) {
        either.add(
            new BoostQuery(new PrefixQuery(new Term(CONTENT, token)), PREFIX_BOOST), Occur.SHOULD);
      }
    } else if (tokens.size() > 1) {
      // One word to whoever typed it, such as "e-mail" or "v2.1", but several to the analyzer.
      either.add(new PhraseQuery(CONTENT, tokens.toArray(String[]::new)), Occur.SHOULD);
    }
    return either.build();
  }

  static String fold(String text) {
    return ANALYZER.normalize(NAME, nfc(text)).utf8ToString();
  }

  // Accents folded away need to be one character with their letter: "é" rather than "e" and a
  // combining accent, as names copied from macOS and text from some PDFs have them.
  private static String nfc(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFC);
  }

  static List<String> tokens(String text) {
    List<String> tokens = new ArrayList<>();
    try (TokenStream stream = ANALYZER.tokenStream(CONTENT, nfc(text))) {
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        tokens.add(term.toString());
      }
      stream.end();
    } catch (IOException e) {
      // Analysing a string in memory does no I/O.
      throw new UncheckedIOException(e);
    }
    return tokens;
  }

  static String escapeWildcards(String text) {
    return text.replaceAll("[*?\\\\]", "\\\\$0");
  }

  @Override
  void closeIndex() throws IOException {
    closeSearchers();
    directory.close();
  }

  /** What one batch did, for the log. */
  private static final class Counts {
    int indexed;
    int removed;
  }

  /**
   * The passage of {@code content} that best matches a search's {@link #query}, cut and marked as
   * this index's own hits are, for a backend that finds hits elsewhere and has their text; null
   * when nothing in the text matches.
   */
  static Snippet snippet(Query query, String content) throws IOException {
    return (Snippet) new SnippetHighlighter().highlightWithoutSearcher(CONTENT, query, content, 1);
  }

  /** Each hit's best passage, as a {@link Snippet}. */
  private static final class SnippetHighlighter extends UnifiedHighlighter {

    SnippetHighlighter(IndexSearcher searcher) {
      super(configure(UnifiedHighlighter.builder(searcher, ANALYZER)));
    }

    // Without an index: the text is given, and analysed again to find the matches.
    SnippetHighlighter() {
      super(configure(UnifiedHighlighter.builderWithoutSearcher(ANALYZER)));
    }

    private static UnifiedHighlighter.Builder configure(UnifiedHighlighter.Builder builder) {
      return builder
          .withMaxLength(TextExtractor.MAX_CHARS)
          // About a sentence's worth, broken between words, with the match in the middle.
          .withBreakIterator(
              () ->
                  LengthGoalBreakIterator.createClosestToLength(
                      BreakIterator.getWordInstance(Locale.ROOT), SNIPPET_LENGTH, 0.5f))
          .withFormatter(SNIPPETS)
          // A hit on the name alone gets no snippet, rather than the text's first lines.
          .withMaxNoHighlightPassages(0)
          .withHandleMultiTermQuery(true);
    }

    Object[] snippets(Query query, int[] docIds) throws IOException {
      return highlightFieldsAsObjects(new String[] {CONTENT}, query, docIds, new int[] {1})
          .get(CONTENT);
    }
  }

  private static boolean isBlank(String text, int from, int to) {
    for (int i = from; i < to; i++) {
      if (!Character.isWhitespace(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static final PassageFormatter SNIPPETS =
      new PassageFormatter() {
        @Override
        public Snippet format(Passage[] passages, String content) {
          if (passages.length == 0) {
            return null;
          }
          Passage passage = passages[0];
          int start = passage.getStartOffset();
          int end = passage.getEndOffset();
          // Cut between words, a passage can start on the last sentence's full stop; it starts
          // at the next word instead, though never past the first match.
          int firstMatch = passage.getNumMatches() > 0 ? passage.getMatchStarts()[0] : end;
          while (start < Math.min(end, firstMatch)
              && (start > 0 && !Character.isLetterOrDigit(content.charAt(start))
                  || Character.isWhitespace(content.charAt(start)))) {
            start++;
          }
          while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
            end--;
          }

          StringBuilder text = new StringBuilder();
          if (!isBlank(content, 0, start)) {
            text.append(ELLIPSIS);
          }
          int shift = text.length() - start;
          text.append(content, start, end);
          if (!isBlank(content, end, content.length())) {
            text.append(ELLIPSIS);
          }

          // In order and without overlaps, which a phrase and its own words would otherwise give.
          List<Snippet.Highlight> matches = new ArrayList<>();
          for (int i = 0; i < passage.getNumMatches(); i++) {
            int from = Math.max(passage.getMatchStarts()[i], start);
            int to = Math.min(passage.getMatchEnds()[i], end);
            if (from < to) {
              matches.add(new Snippet.Highlight(from + shift, to + shift));
            }
          }
          matches.sort(Comparator.comparingInt(Snippet.Highlight::start));
          List<Snippet.Highlight> highlights = new ArrayList<>();
          for (Snippet.Highlight match : matches) {
            Snippet.Highlight previous = highlights.isEmpty() ? null : highlights.getLast();
            if (previous != null && match.start() <= previous.end()) {
              highlights.set(
                  highlights.size() - 1,
                  new Snippet.Highlight(previous.start(), Math.max(previous.end(), match.end())));
            } else {
              highlights.add(match);
            }
          }
          return new Snippet(text.toString(), highlights);
        }
      };
}
