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

public class ElasticApiKeyBackendClient extends AbstractBackendListenerClient {

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

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(ES_URL, "http://localhost:9200/_bulk");
        arguments.addArgument(ES_API_KEY, "YOUR_API_KEY");
        arguments.addArgument(ENVIRONMENT, "perf_cmp_2");
        arguments.addArgument(TYPE, "api");
        arguments.addArgument(TRANSACTION_PREFIX, "TC");
        arguments.addArgument(BATCH_SIZE, "10");
        arguments.addArgument(SAVE_RESPONSE_BODY, "onError"); // onError, always, off
        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        this.elasticUrl = context.getParameter(ES_URL);
        this.apiKey = context.getParameter(ES_API_KEY);
        this.environment = context.getParameter(ENVIRONMENT);
        this.type = context.getParameter(TYPE);
        this.transactionControllerPrefix = context.getParameter(TRANSACTION_PREFIX);
        this.batchSize = context.getIntParameter(BATCH_SIZE, 10);
        this.saveResponseBodyMode = context.getParameter(SAVE_RESPONSE_BODY, "onError");

        this.runId = UUID.randomUUID().toString(); // generate a GUID for this test run

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(5000)
                .build();

        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
    }

    @Override
    public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
        for (SampleResult sample : sampleResults) {
            processSampleResultRecursively(sample, null);
        }
    }

    private void processSampleResultRecursively(SampleResult result, String parentLabel) {
        String label = result.getSampleLabel();
        boolean success = result.isSuccessful();
        long responseTime = result.getTime();
        String responseCode = result.getResponseCode();
        String responseMessage = result.getResponseMessage();
        String timestamp = Instant.ofEpochMilli(result.getTimeStamp()).toString();

        String responseBody = null;

        if ("always".equalsIgnoreCase(saveResponseBodyMode)) {
            responseBody = result.getResponseDataAsString();
        } else if ("onError".equalsIgnoreCase(saveResponseBodyMode) && !success) {
            responseBody = result.getResponseDataAsString();
        }

        if (responseBody != null && responseBody.length() > 2048) {
            responseBody = responseBody.substring(0, 2048) + "...";
        }

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

        boolean isTransactionController = label.startsWith(transactionControllerPrefix);

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
                        "\"run_id\":\"%s\"" +  // add run_id to JSON
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
                runId // insert the GUID
        );

        synchronized (jsonBatch) {
            jsonBatch.add(json);
            if (jsonBatch.size() >= batchSize) {
                flushBatch();
            }
        }

        // process children
        for (SampleResult child : result.getSubResults()) {
            processSampleResultRecursively(child, label);
        }
    }

    private void flushBatch() {
        if (jsonBatch.isEmpty()) {
            return;
        }

        try {
            StringBuilder bulkPayload = new StringBuilder();
            for (String doc : jsonBatch) {
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
            jsonBatch.clear();
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        flushBatch();
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "\\\"").replace("\n", " ");
    }
}
