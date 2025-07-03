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
import java.util.List;

import java.time.Instant;

public class ElasticApiKeyBackendClient extends AbstractBackendListenerClient {

    public static final String ES_URL = "es.url";
    public static final String ES_API_KEY = "es.api.key";
    public static final String ENVIRONMENT = "environment";
    public static final String TYPE = "type";
    public static final String TRANSACTION_PREFIX = "transaction.controller.prefix";

    private CloseableHttpClient httpClient;
    private String elasticUrl;
    private String apiKey;
    private String environment;
    private String type;
    private String transactionControllerPrefix;

    private static final Logger log = LoggerFactory.getLogger(ElasticApiKeyBackendClient.class);

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(ES_URL, "http://localhost:9200/_bulk");
        arguments.addArgument(ES_API_KEY, "YOUR_API_KEY");
        arguments.addArgument(ENVIRONMENT, "perf_cmp_2");
        arguments.addArgument(TYPE, "api");
        arguments.addArgument(TRANSACTION_PREFIX, "TC"); // default prefix
        return arguments;
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        this.elasticUrl = context.getParameter(ES_URL);
        this.apiKey = context.getParameter(ES_API_KEY);
        this.environment = context.getParameter(ENVIRONMENT);
        this.type = context.getParameter(TYPE);
        this.transactionControllerPrefix = context.getParameter(TRANSACTION_PREFIX);

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

    /**
     * Recursively process SampleResult tree
     */
    private void processSampleResultRecursively(SampleResult result, String parentLabel) {
        String label = result.getSampleLabel();
        boolean success = result.isSuccessful();
        long responseTime = result.getTime();
        String responseCode = result.getResponseCode();
        String responseMessage = result.getResponseMessage();

        String timestamp = Instant.ofEpochMilli(result.getTimeStamp()).toString();


        String errorBody = null;
        if (!success) {
            errorBody = result.getResponseDataAsString();
            if (errorBody != null && errorBody.length() > 2048) {
                errorBody = errorBody.substring(0, 2048) + "...";
            }
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

        // detect if this is a Transaction Controller:
        boolean isTransactionController = label.startsWith(transactionControllerPrefix);

        String json = String.format(
                "{\n" +
                        "  \"threadName\": \"%s\",\n" +
                        "  \"label\": \"%s\",\n" +
                        "  \"parentLabel\": \"%s\",\n" +
                        "  \"isTransactionController\": %b,\n" +
                        "  \"success\": %b,\n" +
                        "  \"responseTime\": %d,\n" +
                        "  \"responseCode\": \"%s\",\n" +
                        "  \"responseMessage\": \"%s\",\n" +
                        "  \"errorBody\": \"%s\",\n" +
                        "  \"assertions\": \"%s\",\n" +
                        "  \"time_stamp\": \"%s\",\n" +  // <=== changed from %d to %s
                        "  \"activeThreads\": %d,\n" +
                        "  \"startedThreads\": %d,\n" +
                        "  \"finishedThreads\": %d,\n" +
                        "  \"environment\": \"%s\",\n" +
                        "  \"type\": \"%s\"\n" +
                        "}",
                safe(result.getThreadName()),
                safe(label),
                safe(parentLabel),
                isTransactionController,
                success,
                responseTime,
                safe(responseCode),
                safe(responseMessage),
                safe(errorBody),
                safe(assertionMessages.toString()),
                timestamp,   // ISO8601 string
                activeThreads,
                startedThreads,
                finishedThreads,
                safe(environment),
                safe(type)
        );

        sendToElastic(json);

        // recursively process children
        for (SampleResult child : result.getSubResults()) {
            processSampleResultRecursively(child, label); // propagate parent label
        }
    }

    private void sendToElastic(String json) {
        try {
            HttpPost request = new HttpPost(elasticUrl);
            request.setHeader("Content-Type", "application/json");
            request.setHeader("Authorization", "ApiKey " + apiKey);
            request.setEntity(new StringEntity(json, StandardCharsets.UTF_8));

            log.info("Sending to Elasticsearch: {}", json);

            try (var response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = response.getEntity() != null
                        ? new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8)
                        : "";
                log.info("Elasticsearch response: statusCode={}, body={}", statusCode, responseBody);
            }
        } catch (Exception e) {
            log.error("Error sending data to Elasticsearch: {}", e.getMessage(), e);
        }
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}