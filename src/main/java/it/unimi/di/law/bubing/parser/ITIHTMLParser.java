package it.unimi.di.law.bubing.parser;

/*
 * Copyright (C) 2004-2013 Paolo Boldi, Massimo Santini, and Sebastiano Vigna
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 3 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.sun.webkit.WebPage;
import gr.iti.mklab.reveal.util.ImageUtils;
import gr.iti.mklab.simmo.core.documents.Webpage;
import gr.iti.mklab.simmo.core.items.Image;
import gr.iti.mklab.simmo.core.morphia.DAOManager;
import gr.iti.mklab.simmo.core.morphia.MediaDAO;
import it.unimi.di.law.bubing.Agent;
import it.unimi.di.law.bubing.util.BURL;
import it.unimi.di.law.bubing.util.ByteArrayCharSequence;
import it.unimi.di.law.bubing.util.Util;
import it.unimi.di.law.warc.filters.URIResponse;
import it.unimi.di.law.warc.records.WarcHeader;
import it.unimi.di.law.warc.records.WarcRecord;
import it.unimi.di.law.warc.util.StringHttpMessages;
import it.unimi.dsi.fastutil.io.InspectableFileCachedInputStream;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.lang.ObjectParser;
import it.unimi.dsi.util.TextPattern;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.htmlparser.jericho.*;

import org.apache.commons.httpclient.util.DateParseException;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.apache.ivy.util.DateUtil;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Parameter;
import com.martiansoftware.jsap.SimpleJSAP;
import com.martiansoftware.jsap.Switch;
import com.martiansoftware.jsap.UnflaggedOption;

// RELEASE-STATUS: DIST

/**
 * An ITI HTMLParser. This is a copy of {@link it.unimi.di.law.bubing.parser.HTMLParser}
 * with some changes to add image parsing. Parse and process are the changed methods.
 * Simply extending the original class was not enough, hence this not so "clean" solution
 *
 * @author Katerina Andreadou
 */
public class ITIHTMLParser<T> implements Parser<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ITIHTMLParser.class);

    private static final DateTimeFormatter RFC1123_DATE_TIME_FORMATTER =
            DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                    .withZoneUTC().withLocale(Locale.US);

    public Set<String> keywords = new HashSet<String>();
    public String collectionName;
    private DAOManager manager;

    private Random rand = new Random(System.currentTimeMillis()); 
    
    //private MediaDAO<Image> imageDAO;

    static {
        /* As suggested by Martin Jericho. This should speed up things and avoid problems with
         * server tags embedded in weird places (e.g., JavaScript string literals). Server tags
		 * should not appear in generated HTML anyway. */

        StartTagType.SERVER_COMMON.deregister();
        StartTagType.SERVER_COMMON_COMMENT.deregister();
        StartTagType.SERVER_COMMON_ESCAPED.deregister();
    }

    /**
     * An implementation of a {@link Parser.LinkReceiver} that accumulates the URLs in a public set.
     */
    public final static class SetLinkReceiver implements LinkReceiver {
        /**
         * The set of URLs gathered so far.
         */
        public final Set<URI> urls = new ObjectLinkedOpenHashSet<URI>();

        @Override
        public void location(URI location) {
            urls.add(location);
        }

        @Override
        public void metaLocation(URI location) {
            urls.add(location);
        }

        @Override
        public void metaRefresh(URI refresh) {
            urls.add(refresh);
        }

        @Override
        public void link(URI link) {
            urls.add(link);
        }

        @Override
        public void init(URI responseUrl) {
            urls.clear();
        }

        @Override
        public Iterator<URI> iterator() {
            return urls.iterator();
        }

        @Override
        public int size() {
            return urls.size();
        }
    }

    /**
     * A class computing the digest of a page.
     * <p/>
     * <p>The page is somewhat simplified before being passed (as a sequence of bytes obtained
     * by breaking each character into the upper and lower byte) to a {@link Hasher}.
     * All start/end tags are case-normalized, and their whole content (except for the
     * element-type name) is removed.
     * An exception is made for <samp>SRC</samp> attribute of
     * <samp>FRAME</samp> and <samp>IFRAME</samp> elements, as they are necessary to
     * distinguish correctly framed pages without alternative text. The attributes will be resolved
     * w.r.t. the {@linkplain #init(URI) URL associated to the page}.
     * Moreover, non-HTML tags are substituted with a special tag <samp>unknown</samp>.
     * <p/>
     * <p>For what concerns the text, all digits are substituted by a whitespace, and nonempty whitespace maximal sequences are coalesced
     * to a single space. Tags are considered as a non-whitespace character.
     * <p/>
     * <p>To avoid clashes between digests coming from different sites, you can optionally set a URL
     * (passed to the {@link #init(URI)} method) whose scheme+authority will be used to update the digest before adding the actual text page.
     */
    public final static class DigestAppendable implements Appendable {
        private static final boolean DEBUG = false;
        private PrintStream debugStream;
        private File debugFile;

        /**
         * Cached byte representations of all opening tags. The map must be queried using {@linkplain HTMLElementName Jericho names}.
         */
        protected static final Reference2ObjectOpenHashMap<String, byte[]> startTags;

        /**
         * Cached byte representations of all closing tags. The map must be queried using {@linkplain HTMLElementName Jericho names}.
         */
        protected static final Reference2ObjectOpenHashMap<String, byte[]> endTags;

        static {
            final List<String> elementNames = HTMLElements.getElementNames();
            startTags = new Reference2ObjectOpenHashMap<String, byte[]>(elementNames.size());
            endTags = new Reference2ObjectOpenHashMap<String, byte[]>(elementNames.size());

            // Set up defaults for bizarre element types
            startTags.defaultReturnValue(Util.toByteArray("<unknown>"));
            endTags.defaultReturnValue(Util.toByteArray("</unknown>"));

            // Scan all known element types and fill startTag/endTag
            for (String name : elementNames) {
                startTags.put(name, Util.toByteArray("<" + name + ">"));
                endTags.put(name, Util.toByteArray("</" + name + ">"));
            }
        }

        /**
         * The message digest used to compute the digest.
         */
        protected final HashFunction hashFunction;
        /**
         * The hasher currently used to compute the digest.
         */
        protected Hasher hasher;
        /**
         * True iff the last character appended was a space.
         */
        protected boolean lastAppendedWasSpace;
        /**
         * The last returne digest, or {@code null} if {@link #init(URI)} has been called but {@link #digest()} hasn't.
         */
        protected byte[] digest;


        /**
         * Create a digest appendable using a given hash function.
         *
         * @param hashFunction the hash function used to digest.
         */
        public DigestAppendable(final HashFunction hashFunction) {
            this.hashFunction = hashFunction;
            if (DEBUG) try {
                debugStream = new PrintStream(debugFile = File.createTempFile("tempfile", ".tmp"));
                System.err.println("Debug file: " + debugFile);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Initializes the digest computation.
         *
         * @param url a URL, or {@code null} for no URL. In the former case, the host name will be used to initialize the digest.
         */
        public void init(final URI url) {
            hasher = hashFunction.newHasher();
            digest = null;

            if (url != null) {
                // Note that we need to go directly to the hasher to encode explicit IP addresses
                hasher.putUnencodedChars(url.getHost());
                hasher.putByte((byte) 0);
                if (DEBUG) debugStream.append(url.getHost());
            }
            lastAppendedWasSpace = false;
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) {
            // Hopefully this will soon be inlined by the jvm: no need to duplicate the code! :-)
            for (int i = start; i < end; i++) append(csq.charAt(i));
            return this;
        }

        @Override
        public Appendable append(char c) {
            if (Character.isWhitespace(c) || Character.isDigit(c)) {
                if (!lastAppendedWasSpace) {
                    hasher.putChar(' ');
                    if (DEBUG) debugStream.append(' ');
                    lastAppendedWasSpace = true;
                }
            } else {
                hasher.putChar(c);
                if (DEBUG) debugStream.append(c);
                lastAppendedWasSpace = false;
            }
            return this;
        }

        @Override
        public Appendable append(CharSequence csq) {
            return append(csq, 0, csq.length());
        }

        private void append(byte[] a) {
            hasher.putBytes(a);
            if (DEBUG) for (byte b : a) debugStream.append((char) b);
        }

        public byte[] digest() {
            if (digest == null) digest = hasher.hash().asBytes();
            return digest;
        }

        public void startTag(final StartTag startTag) {
            final String name = startTag.getName();
            append(startTags.get(name));

            // IFRAME or FRAME + SRC
            if (name == HTMLElementName.IFRAME || name == HTMLElementName.FRAME) {
                String s = startTag.getAttributeValue("src");
                if (s != null) {
                    append('\"');
                    append(s);
                    append('\"');
                }
            }
            lastAppendedWasSpace = false;
        }

        public void endTag(final EndTag endTag) {
            append(endTags.get(endTag.getName()));
            lastAppendedWasSpace = false;
        }
    }

    /**
     * The pattern prefixing the URL in a <samp>META </samp> <samp>HTTP-EQUIV </samp> element of refresh type.
     */
    protected static final TextPattern URLEQUAL_PATTERN = new TextPattern("URL=", TextPattern.CASE_INSENSITIVE);
    /**
     * The size of the internal Jericho buffer.
     */
    public static final int CHAR_BUFFER_SIZE = 128 * 1024;

    /**
     * The character buffer. It is set up at construction time, but it can be changed later.
     */
    protected final char[] buffer;
    /**
     * The charset we guessed for the last response.
     */
    protected String guessedCharset;
    /**
     * An object emboding the digest logic, or {@code null} for no digest computation.
     */
    protected final DigestAppendable digestAppendable;
    /**
     * A text processor, or {@code null}.
     */
    protected final TextProcessor<T> textProcessor;
    /**
     * The location URL from headers of the last response, if any, or {@code null}.
     */
    protected URI location;
    /**
     * The location URL from <samp>META</samp> elements of the last response, if any, or {@code null}.
     */
    protected URI metaLocation;
    /**
     * If <code>true</code>, pages with the same content but with different authorities are considered duplicates.
     */
    protected boolean crossAuthorityDuplicates;

    /**
     * Builds a parser for link extraction and, possibly, digesting a page. By default, only pages from within the same
     * scheme+authority may be considered to be duplicates.
     *
     * @param hashFunction the hash function used to digest, {@code null} if no digesting will be performed.
     */
    public ITIHTMLParser(final HashFunction hashFunction) {
        this(hashFunction, false);
    }

    /**
     * Builds a parser for link extraction and, possibly, digesting a page.
     *
     * @param hashFunction             the hash function used to digest, {@code null} if no digesting will be performed.
     * @param textProcessor            a text processor, or {@code null} if no text processing is required.
     * @param crossAuthorityDuplicates if <code>true</code>, pages with different scheme+authority but with the same content will be considered to be duplicates, as long
     *                                 as they are assigned to the same {@link Agent}.
     * @param bufferSize               the fixed size of the internal buffer; if zero, the buffer will be dynamic.
     */
    public ITIHTMLParser(final HashFunction hashFunction, final TextProcessor<T> textProcessor, final boolean crossAuthorityDuplicates, final int bufferSize) {
        buffer = bufferSize != 0 ? new char[bufferSize] : null;
        digestAppendable = hashFunction == null ? null : new DigestAppendable(hashFunction);
        this.textProcessor = textProcessor;
        this.crossAuthorityDuplicates = crossAuthorityDuplicates;
    }

    /**
     * Builds a parser with a fixed buffer of {@link #CHAR_BUFFER_SIZE} characters for link extraction and, possibly, digesting a page.
     *
     * @param hashFunction             the hash function used to digest, {@code null} if no digesting will be performed.
     * @param crossAuthorityDuplicates if <code>true</code>, pages with different scheme+authority but with the same content will be considered to be duplicates, as long
     *                                 as they are assigned to the same {@link Agent}.
     */
    public ITIHTMLParser(final HashFunction hashFunction, final boolean crossAuthorityDuplicates) {
        this(hashFunction, null, crossAuthorityDuplicates, CHAR_BUFFER_SIZE);
    }

    /**
     * Builds a parser with a fixed buffer of {@link #CHAR_BUFFER_SIZE} characters for link extraction and, possibly, digesting a page.
     *
     * @param hashFunction             the hash function used to digest, {@code null} if no digesting will be performed.
     * @param textProcessor            a text processor, or {@code null} if no text processing is required.
     * @param crossAuthorityDuplicates if <code>true</code>, pages with different scheme+authority but with the same content will be considered to be duplicates, as long
     *                                 as they are assigned to the same {@link Agent}.
     */
    public ITIHTMLParser(final HashFunction hashFunction, final TextProcessor<T> textProcessor, final boolean crossAuthorityDuplicates) {
        this(hashFunction, textProcessor, crossAuthorityDuplicates, CHAR_BUFFER_SIZE);
    }

    /**
     * Builds a parser with a fixed buffer of {@link #CHAR_BUFFER_SIZE} characters for link extraction and, possibly, digesting a page. (No cross-authority duplicates are considered)
     *
     * @param messageDigest the name of a message-digest algorithm, or the empty string if no digest will be computed.
     * @throws NoSuchAlgorithmException
     */
    public ITIHTMLParser(final String messageDigest) throws NoSuchAlgorithmException {
        this(BinaryParser.forName(messageDigest));
    }

    /**
     * Builds a parser with a fixed buffer of {@link #CHAR_BUFFER_SIZE} characters for link extraction and, possibly, digesting a page.
     *
     * @param messageDigest            the name of a message-digest algorithm, or the empty string if no digest will be computed.
     * @param crossAuthorityDuplicates a string whose value can only be "true" or "false" that is used to determine if you want to check for cross-authority duplicates.
     * @throws NoSuchAlgorithmException
     */
    public ITIHTMLParser(final String messageDigest, final String crossAuthorityDuplicates) throws NoSuchAlgorithmException {
        this(BinaryParser.forName(messageDigest), Util.parseBoolean(crossAuthorityDuplicates));
    }

    /**
     * Builds a parser with a fixed buffer of {@link #CHAR_BUFFER_SIZE} characters for link extraction and, possibly, digesting a page.
     *
     * @param messageDigest            the name of a message-digest algorithm, or the empty string if no digest will be computed.
     * @param textProcessorSpec        the specification of a text processor that will be passed to an {@link ObjectParser}.
     * @param crossAuthorityDuplicates a string whose value can only be "true" or "false" that is used to determine if you want to check for cross-authority duplicates.
     * @throws NoSuchAlgorithmException
     */
    @SuppressWarnings("unchecked")
    public ITIHTMLParser(final String messageDigest, final String textProcessorSpec, final String crossAuthorityDuplicates) throws NoSuchAlgorithmException, IllegalArgumentException, ClassNotFoundException, IllegalAccessException, InvocationTargetException, InstantiationException, NoSuchMethodException, IOException {
        this(BinaryParser.forName(messageDigest), (TextProcessor<T>) ObjectParser.fromSpec(textProcessorSpec), Util.parseBoolean(crossAuthorityDuplicates));
    }

    /**
     * Builds a parser for link extraction that does not compute digests.
     */
    public ITIHTMLParser() {
        this(null, null, false, 0);
    }

    /**
     * Pre-process a string that represents a raw link found in the page, trying to derelativize it. If it succeeds, the
     * resulting URL is passed to the link receiver.
     *
     * @param linkReceiver the link receiver that will receive the resulting URL.
     * @param base         the base URL to be used to derelativize the link.
     * @param s            the raw link to be derelativized.
     */
    protected void process(final LinkReceiver linkReceiver, final URI base, final String s, final String text, Webpage wp, boolean checkImage) {
        if (s == null) return;
        URI url = BURL.parse(s);
        if (url == null) return;
        URI resolved = base.resolve(url);
        if (checkImage && ImageUtils.isImageUrl(resolved.toString())) {
            String resolvedStr = resolved.toString();
            Image item = new Image();
            item.setUrl(resolvedStr);
            item.setTitle(text);
            item.setWebPageUrl(wp.getUrl());
            item.setLastModifiedDate(wp.getLastModifiedDate());
            String itId = "Web#" + resolvedStr.hashCode();
            item.setId(itId);
            wp.setId(itId);
            item.setCrawlDate(wp.getCrawlDate());
            item.setCreationDate(getRandomDate(wp.getCrawlDate()));
            wp.addItem(item);
            processImageURL(wp);
        } else
            linkReceiver.link(resolved);
    }

    private final BloomFilter<CharSequence> UNIQUE_IMAGE_URLS = BloomFilter.create(Funnels.unencodedCharsFunnel(), 100000);

    public void processImageURL(Webpage wp) {
        Image image = (Image) wp.getItems().get(0);
        if (image == null) return;
        String title = image.getTitle();
        String imageUri = image.getUrl();
        LOGGER.debug("Process image URL " + imageUri + " " + title);
        if (title == null || imageUri == null)
            return;
        boolean keywordFound = false;
        for (String keyword : keywords) {
            if ((title != null && title.toLowerCase().contains(keyword.toLowerCase())) || imageUri.toLowerCase().contains(keyword.toLowerCase())) {
                keywordFound = true;
                break;
            }
        }
        if (!keywordFound)
            return;
        if (!UNIQUE_IMAGE_URLS.mightContain(imageUri)) {
            // Put it in the bloom filter even if it is not saved eventually
            // to avoid doing the same checks for the same image a second time
            UNIQUE_IMAGE_URLS.put(imageUri);
            manager.saveWebpage(wp);
        }
    }

    /**
     * Checks if an image URI is valid and if it is, it downloads the image,
     * performs feature extraction, indexes and stores the metadata in a MongoDB.
     *
     * @param pageUri,  the page URI
     * @param base,     the base URI
     * @param imageUri, the image URI
     * @param altText,  the alt text or any text extracted from the html tag
     * @throws MalformedURLException
     * @throws IOException
     */
    public void processImageURL(URI pageUri, URI base, String imageUri, String altText, int width, int height) throws MalformedURLException, IOException {

        boolean keywordFound = false;
        for (String keyword : keywords) {
            if ((altText != null && altText.toLowerCase().contains(keyword.toLowerCase())) || imageUri.toLowerCase().contains(keyword.toLowerCase())) {
                keywordFound = true;
                break;
            }
        }
        if (!keywordFound)
            return;
        URI url = BURL.parse(imageUri);
        if (url != null) {
            URI resolved = base.resolve(url);
            String resolvedStr = resolved.toString();

            //avoid trying to index the same image multiple times
            if (!UNIQUE_IMAGE_URLS.mightContain(resolvedStr)) {
                // Put it in the bloom filter even if it is not saved eventually
                // to avoid doing the same checks for the same image a second time
                UNIQUE_IMAGE_URLS.put(resolvedStr);

                Webpage wp = new Webpage();
                wp.setUrl(pageUri.toString());

                final URLConnection con = resolved.toURL().openConnection();

                Image item = new Image();
                item.setUrl(resolvedStr);
                item.setTitle(altText);
                item.setWidth(width);
                item.setHeight(height);
                item.setWebPageUrl(pageUri.toString());
                item.setLastModifiedDate(new Date(con.getLastModified()));
                item.setId("Web#" + resolvedStr.hashCode());
                item.setCrawlDate(new Date());
                item.setCreationDate(getRandomDate());
                wp.addItem(item);
                manager.saveWebpage(wp);
                //imageDAO.save(item);
            }
        }
    }

    @Override
    public byte[] parse(final URI uri, final HttpResponse httpResponse, final LinkReceiver linkReceiver) throws IOException {
        guessedCharset = "ISO-8859-1";

        final HttpEntity entity = httpResponse.getEntity();

        // TODO: check if it will make sense to use getValue() of entity
        // Try to guess using headers
        final Header contentTypeHeader = entity.getContentType();
        if (contentTypeHeader != null) {
            final String headerCharset = getCharsetNameFromHeader(contentTypeHeader.getValue());
            if (headerCharset != null) guessedCharset = headerCharset;
        }

        final InputStream contentStream = entity.getContent();

		/* Note that the bubing-guessed-charset header and the header guessed by inspecting
            the entity content are complementary. The first is supposed to appear when parsing
			a store, the second while crawling. They should be aligned. This is a bit tricky,
			but we want to avoid the dependency on "rewindable" streams while parsing. */

        final Header bubingGuessedCharsetHeader = httpResponse instanceof WarcRecord ? ((WarcRecord) httpResponse).getWarcHeader(WarcHeader.Name.BUBING_GUESSED_CHARSET) : null;

        if (bubingGuessedCharsetHeader != null) guessedCharset = bubingGuessedCharsetHeader.getValue();
        else {
            if (contentStream instanceof InspectableFileCachedInputStream) {
                final InspectableFileCachedInputStream inspectableStream = (InspectableFileCachedInputStream) contentStream;
                final String metaCharset = getCharsetName(inspectableStream.buffer, inspectableStream.inspectable);
                if (metaCharset != null) guessedCharset = metaCharset;
            }
        }


        if (LOGGER.isDebugEnabled()) LOGGER.debug("Guessing charset \"{}\" for URL {}", guessedCharset, uri);

        Charset charset = Charsets.ISO_8859_1; // Fallback
        try {
            charset = Charset.forName(guessedCharset);
        } catch (IllegalCharsetNameException e) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Response for {} contained an illegal charset name: \"{}\"", uri, guessedCharset);
        } catch (UnsupportedCharsetException e) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Response for {} contained an unsupported charset: \"{}\"", uri, guessedCharset);
        }

        linkReceiver.init(uri);
        if (textProcessor != null) textProcessor.init(uri);

        // Get location if present
        location = null;
        metaLocation = null;

        final Header locationHeader = httpResponse.getFirstHeader(HttpHeaders.LOCATION);
        if (locationHeader != null) {
            final URI location = BURL.parse(locationHeader.getValue());
            if (location != null) {
                // This shouldn't happen by standard, but people unfortunately does it.
                if (!location.isAbsolute() && LOGGER.isDebugEnabled())
                    LOGGER.debug("Found relative header location URL: \"{}\"", location);
                linkReceiver.location(this.location = uri.resolve(location));
            }
        }

        @SuppressWarnings("resource")
        final StreamedSource streamedSource = new StreamedSource(new InputStreamReader(contentStream, charset));
        streamedSource.setLogger(null);
        if (buffer != null) streamedSource.setBuffer(buffer);
        if (digestAppendable != null) digestAppendable.init(crossAuthorityDuplicates ? null : uri);
        URI base = uri;

        int lastSegmentEnd = 0;
        int inSpecialText = 0;
        for (Segment segment : streamedSource) {
            if (segment.getEnd() > lastSegmentEnd) {
                lastSegmentEnd = segment.getEnd();
                if (segment instanceof StartTag) {
                    final StartTag startTag = (StartTag) segment;
                    if (startTag.getTagType() != StartTagType.NORMAL) continue;
                    final String name = startTag.getName();

                    if ((name == HTMLElementName.STYLE || name == HTMLElementName.SCRIPT) && !startTag.isSyntacticalEmptyElementTag())
                        inSpecialText++;


                    if (digestAppendable != null) digestAppendable.startTag(startTag);
                    // TODO: detect flow breakers
                    if (linkReceiver == null) continue; // No link receiver, nothing to do.

                    Webpage wp = new Webpage();
                    wp.setUrl(uri.toString());
                    wp.setCrawlDate(new Date());
                    final Header lastModifiedHeader = httpResponse.getFirstHeader(HttpHeaders.LAST_MODIFIED);
                    Date webpageLastModifiedDate = null;
                    if (lastModifiedHeader != null) {
                        webpageLastModifiedDate = RFC1123_DATE_TIME_FORMATTER.parseLocalDate(lastModifiedHeader.getValue()).toDate();
                        wp.setLastModifiedDate(webpageLastModifiedDate);
                    }

                    if (name == HTMLElementName.META) {
                        String nameAttr = startTag.getAttributeValue("name");
                        if ("description".equalsIgnoreCase(nameAttr)) {
                            wp.setDescription(startTag.getAttributeValue("content"));
                        } else if ("author".equalsIgnoreCase(nameAttr)) {
                            wp.setSource(startTag.getAttributeValue("content"));
                        } else if ("og:title".equalsIgnoreCase(nameAttr)) {
                            wp.setTitle(startTag.getAttributeValue("content"));
                        } else {
                            if ("keywords".equalsIgnoreCase(nameAttr)) {
                                String keywords = startTag.getAttributeValue("content");
                                if (keywords != null) {
                                    wp.setTags(new HashSet<>(Arrays.asList(keywords.split(","))));
                                }
                            }
                        }
                    }
                    // IFRAME or FRAME + SRC
                    else if (name == HTMLElementName.IFRAME || name == HTMLElementName.FRAME || name == HTMLElementName.EMBED)
                        process(linkReceiver, base, startTag.getAttributeValue("src"), startTag.getAttributeValue("name"), wp, true);
                    else if (name == HTMLElementName.IMG) {
                        String IMGsrc = startTag.getAttributeValue("src");
                        if (IMGsrc == null)
                            continue;
                        URI url = BURL.parse(IMGsrc);
                        if (url == null) continue;
                        URI resolved = base.resolve(url);
                        String resolveStr = resolved.toString();
                        Image item = new Image();
                        item.setTitle(startTag.getAttributeValue("alt"));
                        item.setWebPageUrl(uri.toString());
                        item.setLastModifiedDate(webpageLastModifiedDate);
                        item.setCrawlDate(wp.getCrawlDate());
                        item.setCreationDate(getRandomDate(wp.getCrawlDate()));
                        item.setUrl(resolveStr);
                        String gId = "Web#" + resolveStr.hashCode();
                        item.setId(gId);
                        wp.setId(gId);
                        if (startTag.getAttributeValue("width") != null && startTag.getAttributeValue("height") != null) {
                            try {
                                int width = Integer.parseInt(startTag.getAttributeValue("width"));
                                int height = Integer.parseInt(startTag.getAttributeValue("height"));
                                if (ImageUtils.isImageSmall(width, height))
                                    continue;
                                else {
                                    item.setWidth(width);
                                    item.setHeight(height);
                                }
                            } catch (NumberFormatException nfe) {
                                //ignore
                            }
                        }
                        wp.addItem(item);
                        processImageURL(wp);

                    } else if (name == HTMLElementName.SCRIPT)
                        process(linkReceiver, base, startTag.getAttributeValue("src"), null, wp, false);
                    else if (name == HTMLElementName.OBJECT)
                        process(linkReceiver, base, startTag.getAttributeValue("data"), startTag.getAttributeValue("name"), wp, true);
                    else if (name == HTMLElementName.A || name == HTMLElementName.AREA || name == HTMLElementName.LINK)
                        process(linkReceiver, base, startTag.getAttributeValue("href"), null, wp, true);
                    else if (name == HTMLElementName.BASE) {
                        String s = startTag.getAttributeValue("href");
                        if (s != null) {
                            final URI link = BURL.parse(s);
                            if (link != null) {
                                if (link.isAbsolute()) base = link;
                                else if (LOGGER.isDebugEnabled()) LOGGER.debug("Found relative BASE URL: \"{}\"", link);
                            }
                        }
                    }

                    // META REFRESH/LOCATION
                    else if (name == HTMLElementName.META) {
                        final String equiv = startTag.getAttributeValue("http-equiv");
                        final String content = startTag.getAttributeValue("content");
                        if (equiv != null && content != null) {
                            equiv.toLowerCase();

                            // http-equiv="refresh" content="0;URL=http://foo.bar/..."
                            if (equiv.equals("refresh")) {

                                final int pos = URLEQUAL_PATTERN.search(content);
                                if (pos != -1) {
                                    final String urlPattern = content.substring(pos + URLEQUAL_PATTERN.length());
                                    final URI refresh = BURL.parse(urlPattern);
                                    if (refresh != null) {
                                        // This shouldn't happen by standard, but people unfortunately does it.
                                        if (!refresh.isAbsolute() && LOGGER.isDebugEnabled())
                                            LOGGER.debug("Found relative META refresh URL: \"{}\"", urlPattern);
                                        linkReceiver.metaRefresh(base.resolve(refresh));
                                    }
                                }
                            }

                            // http-equiv="location" content="http://foo.bar/..."
                            if (equiv.equals("location")) {
                                final URI metaLocation = BURL.parse(content);
                                if (metaLocation != null) {
                                    // This shouldn't happen by standard, but people unfortunately does it.
                                    if (!metaLocation.isAbsolute() && LOGGER.isDebugEnabled())
                                        LOGGER.debug("Found relative META location URL: \"{}\"", content);
                                    linkReceiver.metaLocation(this.metaLocation = base.resolve(metaLocation));
                                }
                            }
                        }
                    }
                } else if (segment instanceof EndTag) {
                    final EndTag endTag = (EndTag) segment;
                    final String name = endTag.getName();
                    if (name == HTMLElementName.STYLE || name == HTMLElementName.SCRIPT) {
                        inSpecialText = Math.max(0, inSpecialText - 1); // Ignore extra closing tags
                    }

                    if (digestAppendable != null) {
                        if (endTag.getTagType() != EndTagType.NORMAL) continue;
                        digestAppendable.endTag(endTag);
                    }
                } else if (inSpecialText == 0) {
                    if (textProcessor != null) {
                        if (segment instanceof CharacterReference)
                            ((CharacterReference) segment).appendCharTo(textProcessor);
                        else textProcessor.append(segment);
                    }
                    if (digestAppendable != null) {
                        if (segment instanceof CharacterReference)
                            ((CharacterReference) segment).appendCharTo(digestAppendable);
                        else digestAppendable.append(segment);
                    }
                }
            }
        }

        if (DigestAppendable.DEBUG)
            if (digestAppendable != null) {
                System.err.println("Closing " + digestAppendable.debugFile + " for " + uri);
                digestAppendable.debugStream.close();
            }

        return digestAppendable != null ? digestAppendable.digest() : null;
    }

    @Override
    public String guessedCharset() {
        return guessedCharset;
    }

    /**
     * Returns the BURL location header, if present; if it is not present, but the page contains a valid metalocation, the latter
     * is returned. Otherwise, {@code null} is returned.
     *
     * @return the location (or metalocation), if present; {@code null} otherwise.
     */
    public URI location() {
        //TODO: see if we must derelativize
        if (location != null) return location;
        else if (metaLocation != null) return metaLocation;
        else return null;
    }

    /**
     * Used by {@link #getCharsetName(byte[], int)}.
     */
    protected static final TextPattern META_PATTERN = new TextPattern("<meta", TextPattern.CASE_INSENSITIVE);
    /**
     * Used by {@link #getCharsetName(byte[], int)}.
     */
    protected static final Pattern HTTP_EQUIV_PATTERN = Pattern.compile(".*http-equiv\\s*=\\s*('|\")?content-type('|\")?.*", Pattern.CASE_INSENSITIVE);
    /**
     * Used by {@link #getCharsetName(byte[], int)}.
     */
    protected static final Pattern CONTENT_PATTERN = Pattern.compile(".*content\\s*=\\s*('|\")([^'\"]*)('|\").*", Pattern.CASE_INSENSITIVE);
    /**
     * Used by {@link #getCharsetName(byte[], int)}.
     */
    protected static final Pattern CHARSET_PATTERN = Pattern.compile(".*charset\\s*=\\s*(([\\041-\\0176&&[^<>\\{\\}\\\\/:,;@?=]])+|\"[^\"]*\").*", Pattern.CASE_INSENSITIVE);

    /**
     * Returns the charset name as indicated by a <samp>META</samp>
     * <samp>HTTP-EQUIV</samp> element, if
     * present, interpreting the provided byte array as a sequence of
     * ISO-8859-1-encoded characters. Only the first such occurrence is considered (even if
     * it might not correspond to a valid or available charset).
     * <p/>
     * <p><strong>Beware</strong>: it might not work if the
     * <em>value</em> of some attribute in a <code>meta</code> tag
     * contains a string matching (case insensitively) the r.e.
     * <code>http-equiv\s*=\s*('|")content-type('|")</code>, or
     * <code>content\s*=\s*('|")[^"']*('|")</code>.
     *
     * @param buffer a buffer containing raw bytes that will be interpreted as ISO-8859-1 characters.
     * @param length the number of significant bytes in the buffer.
     * @return the charset name, or {@code null} if no
     * charset is specified; note that the charset might be not valid or not available.
     */
    public static String getCharsetName(final byte buffer[], final int length) {
        int start = 0;
        while ((start = META_PATTERN.search(buffer, start, length)) != -1) {

			/* Look for attribute http-equiv with value content-type,
             * if present, look for attribute content and, if present,
			 * return its value. */

            int end = start;
            while (end < length && buffer[end] != '>') end++; // Look for closing '>'
            if (end == length) return null; // No closing '>'

            final ByteArrayCharSequence tagContent = new ByteArrayCharSequence(buffer, start + META_PATTERN.length(), end - start - META_PATTERN.length());
            if (HTTP_EQUIV_PATTERN.matcher(tagContent).matches()) {
                final Matcher m = CONTENT_PATTERN.matcher(tagContent);
                if (m.matches()) return getCharsetNameFromHeader(m.group(2)); // got it!
            }

            start = end + 1;
        }

        return null; // no '<meta' found
    }

    /**
     * Extracts the charset name from the header value of a <samp>content-type</samp>
     * header using a regular expression.
     * <p/>
     * <strong>Warning</strong>: it might not work if someone puts the string <samp>charset=</samp>
     * in a string inside some attribute/value pair.
     *
     * @param headerValue The value of a <samp>content-type</samp> header.
     * @return the charset name, or {@code null} if no
     * charset is specified; note that the charset might be not valid or not available.
     */
    public static String getCharsetNameFromHeader(final String headerValue) {
        final Matcher m = CHARSET_PATTERN.matcher(headerValue);
        if (m.matches()) {
            final String s = m.group(1);
            int start = 0, end = s.length();
            // TODO: we discard delimiting single/double quotes; is it necessary?
            if (end > 0 && (s.charAt(0) == '\"' || s.charAt(0) == '\'')) start = 1;
            if (end > 0 && (s.charAt(end - 1) == '\"' || s.charAt(end - 1) == '\'')) end--;
            if (start < end) return s.substring(start, end);
        }
        return null;
    }

    @Override
    public boolean apply(final URIResponse uriResponse) {
        final Header contentType = uriResponse.response().getEntity().getContentType();
        return contentType != null && contentType.getValue().startsWith("text/");
    }

    @Override
    public ITIHTMLParser<T> clone() {
        return new ITIHTMLParser<T>(digestAppendable == null ? null : digestAppendable.hashFunction, textProcessor == null ? null : textProcessor.copy(), crossAuthorityDuplicates, CHAR_BUFFER_SIZE);
    }

    @Override
    public ITIHTMLParser<T> copy() {
        return clone();
    }

    @Override
    public T result() {
        return textProcessor == null ? null : textProcessor.result();
    }

    public void setIndexParameters(String collectionName, Set<String> keywords) {
        this.collectionName = collectionName;
        this.keywords = keywords;
        manager = new DAOManager(collectionName);
        //imageDAO = new MediaDAO<>(Image.class, collectionName);
    }

    public static void main(String arg[]) throws IllegalArgumentException, IOException, URISyntaxException, JSAPException, NoSuchAlgorithmException {

        //keywords.add("cameron");
        final SimpleJSAP jsap = new SimpleJSAP(ITIHTMLParser.class.getName(), "Produce the digest of a page: the page is downloaded or passed as argument by specifying a file",
                new Parameter[]{
                        new UnflaggedOption("url", JSAP.STRING_PARSER, JSAP.REQUIRED, "The url of the page."),
                        new Switch("crossAuthorityDuplicates", 'c', "cross-authority-duplicates"),
                        new FlaggedOption("charBufferSize", JSAP.INTSIZE_PARSER, Integer.toString(CHAR_BUFFER_SIZE), JSAP.NOT_REQUIRED, 'b', "buffer", "The size of the parser character buffer (0 for dynamic sizing)."),
                        new FlaggedOption("file", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.NOT_REQUIRED, 'f', "file", "The page to be processed."),
                        new FlaggedOption("digester", JSAP.STRING_PARSER, "MD5", JSAP.NOT_REQUIRED, 'd', "digester", "The digester to be used.")
                });

        JSAPResult jsapResult = jsap.parse(arg);
        if (jsap.messagePrinted()) System.exit(1);

        final String url = jsapResult.getString("url");
        final String digester = jsapResult.getString("digester");
        final boolean crossAuthorityDuplicates = jsapResult.userSpecified("crossAuthorityDuplicates");
        final int charBufferSize = jsapResult.getInt("charBufferSize");

        final ITIHTMLParser<Void> htmlParser = new ITIHTMLParser<Void>(BinaryParser.forName(digester), (TextProcessor<Void>) null, crossAuthorityDuplicates, charBufferSize);
        final SetLinkReceiver linkReceiver = new SetLinkReceiver();
        final byte[] digest;

        if (!jsapResult.userSpecified("file")) {
            final URI uri = new URI(url);
            final HttpGet request = new HttpGet(uri);
            request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            digest = htmlParser.parse(uri, HttpClients.createDefault().execute(request), linkReceiver);
        } else {
            final String file = jsapResult.getString("file");
            String content = IOUtils.toString(new InputStreamReader(new FileInputStream(file)));
            digest = htmlParser.parse(BURL.parse(url), new StringHttpMessages.HttpResponse(content), linkReceiver);
        }

        //System.out.println("DigestHexString: " + Hex.encodeHexString(digest));
        System.out.println("Links: " + linkReceiver.urls);

        Set<String> urlStrings = new ObjectOpenHashSet<String>();
        for (URI link : linkReceiver.urls) urlStrings.add(link.toString());
        if (urlStrings.size() != linkReceiver.urls.size())
            System.out.println("There are " + linkReceiver.urls.size() + " URIs but " + urlStrings.size() + " strings");

    }

    // 	TODO: temporary code to randomize creation date of web media items. 
    //	TO BE REMOVED!!! 
    private Date getRandomDate() {
    	return getRandomDate(new Date());
    }

    private Date getRandomDate(Date date) {
    	
    	long current = date.getTime();
    	int hours = rand.nextInt(2400);
    	
    	long time = current - (hours*36*1000l);
    	date = new Date(time);
    	return date;
    }
}
