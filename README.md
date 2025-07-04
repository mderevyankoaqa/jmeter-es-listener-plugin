# ElasticApiKeyBackendClient

A custom JMeter Backend Listener that pushes sample results to Elasticsearch using the Elasticsearch Bulk API with API Key authentication.

## Features

✅ Sends performance test metrics from JMeter to Elasticsearch in near real-time  
✅ Supports batching of results for performance  
✅ Works with Elasticsearch API Key security  
✅ Includes a per-test unique `run_id` (GUID) to group results  
✅ Captures assertions, response codes, messages, and optionally the response body  
✅ Supports transaction controllers aggregation  
✅ Built with extendability in mind

## Configuration Parameters

| Parameter                        | Description                                                  | Default                      |
|----------------------------------|--------------------------------------------------------------|------------------------------|
| `es.url`                         | Elasticsearch Bulk API endpoint                              | `http://localhost:9200/_bulk` |
| `es.api.key`                     | Your Elasticsearch API Key                                   | `YOUR_API_KEY`               |
| `environment`                    | Environment name (e.g. `staging`, `prod`)                    | `perf_cmp_2`                 |
| `type`                           | Type of test or component                                    | `api`                        |
| `transaction.controller.prefix`  | Prefix to identify transaction controllers                   | `TC`                         |
| `batch.size`                     | Number of samples to send per bulk request                   | `10`                         |
| `save.response.body`             | Controls saving response body (`always`, `onError`, `off`)   | `onError`                    |

## How it Works

- On test start, the plugin generates a unique `run_id` (UUID) to tag all results.
- Samples are processed recursively, capturing the main sample and any sub-results.
- Results are collected into a JSON batch in Elasticsearch bulk format.
- Once the batch size is reached, the payload is pushed to Elasticsearch via HTTP POST with an `ApiKey` authorization header.
- Metrics include assertions, timing, response codes, and JMeter thread group info.

## Example Elasticsearch Document

```json
{
  "threadName": "Thread Group 1-1",
  "label": "HTTP Request",
  "parentLabel": null,
  "isTransactionController": false,
  "success": true,
  "responseTime": 123,
  "responseCode": "200",
  "responseMessage": "OK",
  "responseBody": "",
  "assertions": "",
  "time_stamp": "2025-07-03T13:45:00Z",
  "activeThreads": 5,
  "startedThreads": 10,
  "finishedThreads": 5,
  "environment": "perf_cmp_2",
  "type": "api",
  "run_id": "fe392fd2-0d82-4b7f-b3e3-d69fa3d30888"
}
