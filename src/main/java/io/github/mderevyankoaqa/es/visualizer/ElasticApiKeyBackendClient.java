package io.github.mderevyankoaqa.es.visualizer;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backend listener for JMeter that sends sample results in bulk to Elasticsearch using API Key authentication.
 * <p>
 * This class extends AbstractBackendListenerClient and processes JMeter sample results recursively,
 * batching them and sending to the configured Elasticsearch _bulk API endpoint.
 * <p>
 * It supports configuration parameters such as Elasticsearch URL, API Key, environment, test type,
 * batch size, transaction controller prefix, and response body saving mode.
 */
public class ElasticApiKeyBackendClient extends AbstractBackendListenerClient {

    // Parameter names for Backend Listener configuration
    public static final String ES_URL = "es.url";
    public static final String ES_API_KEY = "es.api.key";
    public static final String ENVIRONMENT = "environment";
    public static final String TYPE = "type";
    public static final String TRANSACTION_PREFIX = "transaction.controller.prefix";
    public static final String BATCH_SIZE = "batch.size";
    public static final String SAVE_RESPONSE_BODY = "save.response.body";

    private CloseableHttpClient httpClient;
    private String elasticUrl;
    private String apiKey;
    private String environment;
    private String type;
    private String transactionControllerPrefix;
    private int batchSize;
    private String saveResponseBodyMode;
    private String runId;

    private final List<String> jsonBatch = new ArrayList<>();

    private static final Logger log = LoggerFactory.getLogger(ElasticApiKeyBackendClient.class);

    /**
     * Provides the default parameters for this backend listener.
     * These parameters are configurable in JMeter's UI when adding this listener.
     *
     * @return Arguments containing the default configuration parameters.
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(ES_URL, "http://localhost:9200/_bulk");
        arguments.addArgument(ES_API_KEY, "YOUR_API_KEY");
        arguments.addArgument(ENVIRONMENT, "perf_cmp_2");
        arguments.addArgument(TYPE, "api");
        arguments.addArgument(TRANSACTION_PREFIX, "TC");
        arguments.addArgument(BATCH_SIZE, "10");
        arguments.addArgument(SAVE_RESPONSE_BODY, "onError"); // options: onError, always, off
        return arguments;
    }

    /**
     * Initializes the backend listener with configuration from JMeter and prepares the HTTP client.
     * Generates a unique run ID (UUID) for grouping all results from this test run.
     *
     * @param context BackendListenerContext providing the configured parameters.
     * @throws Exception if HTTP client setup fails.
     */
    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        this.elasticUrl = context.getParameter(ES_URL);
        this.apiKey = context.getParameter(ES_API_KEY);
        this.environment = context.getParameter(ENVIRONMENT);
        this.type = context.getParameter(TYPE);
        this.transactionControllerPrefix = context.getParameter(TRANSACTION_PREFIX);
        this.batchSize = context.getIntParameter(BATCH_SIZE, 10);
        this.saveResponseBodyMode = context.getParameter(SAVE_RESPONSE_BODY, "onError");

        // Generate a unique run ID for this test execution
        this.runId = UUID.randomUUID().toString();

        // Configure HTTP client timeouts
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(5000)
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
    }

    /**
     * Handles batches of sample results delivered by JMeter.
     * Each SampleResult is processed recursively to include child samples.
     *
     * @param sampleResults List of SampleResult objects from JMeter.
     * @param context       BackendListenerContext (not used here).
     */
    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        for (SampleResult sample : sampleResults) {
            processSampleResultRecursively(sample, null);
        }
    }

    /**
     * Recursively processes a SampleResult and its children, converting each to JSON and
     * adding it to the batch. When batch size is reached, flushes the batch to Elasticsearch.
     *
     * @param result      The current SampleResult to process.
     * @param parentLabel The label of the parent sample, or null if top-level.
     */
    private void processSampleResultRecursively(SampleResult result, String parentLabel) {
        String label = result.getSampleLabel();
        boolean success = result.isSuccessful();
        long responseTime = result.getTime();
        String responseCode = result.getResponseCode();
        String responseMessage = result.getResponseMessage();
        String timestamp = Instant.ofEpochMilli(result.getTimeStamp()).toString();

        String responseBody = null;

        // Decide whether to save response body based on configured mode
        if ("always".equalsIgnoreCase(saveResponseBodyMode)) {
            responseBody = result.getResponseDataAsString();
        } else if ("onError".equalsIgnoreCase(saveResponseBodyMode) && !success) {
            responseBody = result.getResponseDataAsString();
        }

        // Limit response body size to 2048 chars to avoid large payloads
        if (responseBody != null && responseBody.length() > 2048) {
            responseBody = responseBody.substring(0, 2048) + "...";
        }

        // Collect assertion failure/error messages if any
        StringBuilder assertionMessages = new StringBuilder();
        for (AssertionResult ar : result.getAssertionResults()) {
            if (ar.isFailure() || ar.isError()) {
                assertionMessages
                        .append(ar.getName())
                        .append(": ")
                        .append(ar.getFailureMessage())
                        .append("; ");
            }
        }

        int startedThreads = JMeterContextService.getTotalThreads();
        int activeThreads = JMeterContextService.getNumberOfThreads();
        int finishedThreads = startedThreads - activeThreads;

        // Determine if this sample is from a transaction controller by label prefix
        boolean isTransactionController = label.startsWith(transactionControllerPrefix);

        // Format the JSON document for Elasticsearch
        String json = String.format(
                "{" +
                        "\"threadName\":\"%s\"," +
                        "\"label\":\"%s\"," +
                        "\"parentLabel\":\"%s\"," +
                        "\"isTransactionController\":%b," +
                        "\"success\":%b," +
                        "\"responseTime\":%d," +
                        "\"responseCode\":\"%s\"," +
                        "\"responseMessage\":\"%s\"," +
                        "\"responseBody\":\"%s\"," +
                        "\"assertions\":\"%s\"," +
                        "\"time_stamp\":\"%s\"," +
                        "\"activeThreads\":%d," +
                        "\"startedThreads\":%d," +
                        "\"finishedThreads\":%d," +
                        "\"environment\":\"%s\"," +
                        "\"type\":\"%s\"," +
                        "\"run_id\":\"%s\"" +
                        "}",
                safe(result.getThreadName()),
                safe(label),
                safe(parentLabel),
                isTransactionController,
                success,
                responseTime,
                safe(responseCode),
                safe(responseMessage),
                safe(responseBody),
                safe(assertionMessages.toString()),
                timestamp,
                activeThreads,
                startedThreads,
                finishedThreads,
                safe(environment),
                safe(type),
                runId
        );

        // Add the JSON document to the batch and flush if batch size reached
        synchronized (jsonBatch) {
            jsonBatch.add(json);
            if (jsonBatch.size() >= batchSize) {
                flushBatch();
            }
        }

        // Recursively process sub-results (children)
        for (SampleResult child : result.getSubResults()) {
            processSampleResultRecursively(child, label);
        }
    }

    /**
     * Sends the accumulated batch of JSON documents to Elasticsearch via the Bulk API.
     * Clears the batch after sending.
     */
    private void flushBatch() {
        if (jsonBatch.isEmpty()) {
            return;
        }

        try {
            StringBuilder bulkPayload = new StringBuilder();
            for (String doc : jsonBatch) {
                // Add the bulk API index action line before each document
                bulkPayload.append("{\"index\":{}}\n");
                bulkPayload.append(doc).append("\n");
            }

            HttpPost request = new HttpPost(elasticUrl);
            request.setHeader("Content-Type", "application/x-ndjson");
            request.setHeader("Authorization", "ApiKey " + apiKey);
            request.setEntity(new StringEntity(bulkPayload.toString(), StandardCharsets.UTF_8));

            log.info("Sending bulk payload to Elasticsearch with {} items", jsonBatch.size());

            try (var response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = response.getEntity() != null
                        ? new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8)
                        : "";
                log.info("Elasticsearch response: statusCode={}, body={}", statusCode, responseBody);
            }

        } catch (Exception e) {
            log.error("Error sending batch to Elasticsearch: {}", e.getMessage(), e);
        } finally {
            // Clear the batch regardless of success or failure
            jsonBatch.clear();
        }
    }

    /**
     * Flushes any remaining batched documents and closes the HTTP client on test teardown.
     *
     * @param context BackendListenerContext (not used here).
     * @throws Exception if HTTP client closing fails.
     */
    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        flushBatch();
        if (httpClient != null) {
            httpClient.close();
        }
    }

    /**
     * Helper method to safely escape strings for JSON insertion.
     * Replaces quotes and newlines.
     *
     * @param s input string (may be null)
     * @return safe string for JSON or empty string if null
     */
    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "\\\"").replace("\n", " ");
    }
}