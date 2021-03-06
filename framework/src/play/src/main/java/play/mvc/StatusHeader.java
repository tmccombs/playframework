package play.mvc;

import akka.stream.javadsl.Source;
import akka.util.ByteString;
import akka.util.ByteString$;
import akka.util.ByteStringBuilder;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import play.api.libs.streams.Streams;
import play.http.HttpEntity;
import play.libs.Json;
import scala.None$;
import scala.Option;
import scala.compat.java8.OptionConverters;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * A status with no body
 */
public class StatusHeader extends Result {

    private static final int defaultChunkSize = 1024 * 8;

    public StatusHeader(int status) {
        super(status);
    }

    /**
     * Send the given input stream.
     *
     * The input stream will be sent chunked since there is no specified content length.
     *
     * @param stream The input stream to send.
     * @return The result.
     */
    public Result sendInputStream(InputStream stream) {
        if (stream == null) {
            throw new NullPointerException("Null stream");
        }
        return new Result(status(), HttpEntity.chunked(Streams.inputStreamToSource(stream, defaultChunkSize).asJava(),
                Optional.empty()));
    }

    /**
     * Send the given input stream.
     *
     * @param stream The input stream to send.
     * @param contentLength The length of the content in the stream.
     * @return The result.
     */
    public Result sendInputStream(InputStream stream, long contentLength) {
        if (stream == null) {
            throw new NullPointerException("Null content");
        }
        return new Result(status(), new HttpEntity.Streamed(Streams.inputStreamToSource(stream, defaultChunkSize).asJava(),
                Optional.of(contentLength), Optional.empty()));
    }

    /**
     * Send the given resource.
     * <p>
     * The resource will be loaded from the same classloader that this class comes from.
     *
     * @param resourceName The path of the resource to load.
     */
    public Result sendResource(String resourceName) {
        return sendResource(resourceName, true);
    }

    /**
     * Send the given resource from the given classloader.
     *
     * @param resourceName The path of the resource to load.
     * @param classLoader  The classloader to load it from.
     */
    public Result sendResource(String resourceName, ClassLoader classLoader) {
        return sendResource(resourceName, classLoader, true);
    }

    /**
     * Send the given resource.
     * <p>
     * The resource will be loaded from the same classloader that this class comes from.
     *
     * @param resourceName The path of the resource to load.
     * @param inline       Whether it should be served as an inline file, or as an attachment.
     */
    public Result sendResource(String resourceName, boolean inline) {
        return sendResource(resourceName, this.getClass().getClassLoader(), inline);
    }

    /**
     * Send the given resource from the given classloader.
     *
     * @param resourceName The path of the resource to load.
     * @param classLoader  The classloader to load it from.
     * @param inline       Whether it should be served as an inline file, or as an attachment.
     */
    public Result sendResource(String resourceName, ClassLoader classLoader, boolean inline) {
        return doSendResource(Streams.resourceToSource(classLoader, resourceName, 8192).asJava(),
                Optional.empty(), Optional.of(resourceName), inline);
    }

    /**
     * Sends the given path.
     *
     * @param path The path to send.
     */
    public Result sendPath(Path path) {
        return sendPath(path, false);
    }

    /**
     * Sends the given path.
     *
     * @param path   The path to send.
     * @param inline Whether it should be served as an inline file, or as an attachment.
     */
    public Result sendPath(Path path, boolean inline) {
        return sendPath(path, inline, path.getFileName().toString());
    }

    /**
     * Sends the given path.
     *
     * @param path     The path to send.
     * @param inline   Whether it should be served as an inline file, or as an attachment.
     * @param filename The file name of the path.
     */
    public Result sendPath(Path path, boolean inline, String filename) {
        if (path == null) {
            throw new NullPointerException("null content");
        }
        try {
            return doSendResource(Streams.fileToSource(path.toFile(), 8192).asJava(), Optional.of(Files.size(path)),
                    Optional.of(filename), inline);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sends the given file.
     *
     * @param file The file to send.
     */
    private Result sendFile(File file) {
        return sendFile(file, true);
    }

    /**
     * Sends the given file.
     *
     * @param file The file to send.
     * @param inline  True if the file should be sent inline, false if it should be sent as an attachment.
     */
    public Result sendFile(File file, boolean inline) {
        if (file == null) {
            throw new NullPointerException("null file");
        }
        return doSendResource(Streams.fileToSource(file, 8192).asJava(), Optional.of(file.length()),
                Optional.of(file.getName()), inline);
    }

    /**
     * Send the given file as an attachment.
     *
     * @param file The file to send.
     * @param fileName The name of the attachment
     */
    public Result sendFile(File file, String fileName) {
        if (file == null) {
            throw new NullPointerException("null file");
        }
        return doSendResource(Streams.fileToSource(file, 8192).asJava(), Optional.of(file.length()),
                Optional.of(fileName), true);
    }

    private Result doSendResource(Source<ByteString, ?> data, Optional<Long> contentLength,
                                  Optional<String> resourceName, boolean inline) {
        Map<String, String> headers;
        if (inline || !resourceName.isPresent()) {
            headers = Collections.emptyMap();
        } else {
            headers = Collections.singletonMap(Http.HeaderNames.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + resourceName.get() + "\"");
        }
        return new Result(status(), headers, new HttpEntity.Streamed(
                data, contentLength, resourceName.map(name ->
                        OptionConverters.toJava(play.api.libs.MimeTypes.forFileName(name))
                                .orElse(Http.MimeTypes.BINARY)
        )
        ));
    }

    /**
     * Send a chunked response with the given chunks.
     */
    public Result chunked(Source<ByteString, ?> chunks) {
        return new Result(status(), HttpEntity.chunked(chunks, Optional.empty()));
    }

    /**
     * Send a chunked response with the given chunks.
     *
     * @deprecated Use {@link #chunked(Source)} instead.
     */
    public <T> Result chunked(Results.Chunks<T> chunks) {
        return new Result(status(), HttpEntity.chunked(
                Source.from(Streams.<T>enumeratorToPublisher(chunks.enumerator, Option.<T>empty()))
                        .<ByteString>map(t -> chunks.writable.transform().apply(t)),
                OptionConverters.toJava(chunks.writable.contentType())
        ));
    }

    /**
     * Send a json result.
     */
    public Result sendJson(JsonNode json) {
        return sendJson(json, "utf-8");
    }

    /**
     * Send a json result.
     */
    public Result sendJson(JsonNode json, String charset) {
        if (json == null) {
            throw new NullPointerException("Null content");
        }

        ObjectMapper mapper = Json.mapper();
        ByteStringBuilder builder = ByteString$.MODULE$.newBuilder();

        try {
            JsonGenerator jgen = new JsonFactory(mapper)
                    .createGenerator(new OutputStreamWriter(builder.asOutputStream(), charset));

            mapper.writeValue(jgen, json);
            return new Result(status(), new HttpEntity.Strict(builder.result(),
                    Optional.of("application/json;charset=" + charset)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
