using Common.Experiment;
using Common.Streaming;
using Common.Metric;
using System.Text;
using Kafka.Metric;
using Kafka.Streaming.Redis;
using Common.Http;
using Common.Workers.Seller;
using Common.Workload;
using Common.Workers.Customer;
using DuckDB.NET.Data;
using Common.Workers.Delivery;
using static Common.Services.CustomerService;
using static Common.Services.DeliveryService;
using static Common.Services.SellerService;

namespace Kafka.Workload;

public class KafkaExperimentManager : AbstractExperimentManager
{

    private readonly string redisConnection;
    private readonly List<string> channelsToTrim;

    private readonly KafkaMetricManager metricManager;

    protected static readonly List<TransactionType> eventualCompletionTransactions = new() { TransactionType.CUSTOMER_SESSION, TransactionType.PRICE_UPDATE, TransactionType.UPDATE_PRODUCT };

    public static KafkaExperimentManager BuildKafkaExperimentManager(IHttpClientFactory httpClientFactory, ExperimentConfig config, DuckDBConnection connection)
    {
        return new KafkaExperimentManager(httpClientFactory, DefaultSellerWorker.BuildSellerWorker, DefaultCustomerWorker.BuildCustomerWorker, DefaultDeliveryWorker.BuildDeliveryWorker, config, connection);
    }

    private KafkaExperimentManager(IHttpClientFactory httpClientFactory, BuildSellerWorkerDelegate sellerWorkerDelegate, BuildCustomerWorkerDelegate customerWorkerDelegate, BuildDeliveryWorkerDelegate deliveryWorkerDelegate, ExperimentConfig config, DuckDBConnection connection) :
        base(httpClientFactory, WorkloadManager.BuildWorkloadManager, sellerWorkerDelegate, customerWorkerDelegate, deliveryWorkerDelegate, config, connection)
    {
        this.redisConnection = string.Format("{0}:{1}", config.streamingConfig.host, config.streamingConfig.port);
        this.channelsToTrim = new();
        this.metricManager = new KafkaMetricManager(sellerService, customerService, deliveryService);
    }

    protected override async void PostExperiment()
    {
        await RedisUtils.TrimStreams(redisConnection, channelsToTrim);
        base.PostExperiment();
    }

    /**
     * 1. Trim streams
     * 2. Initialize all customer objects
     * 3. Initialize delivery as a single object, but multithreaded
     */
    protected override async void PreExperiment()
    {
        // cleanup microservice states
        var resps_ = new List<Task<HttpResponseMessage>>();
        foreach (var task in config.postExperimentTasks)
        {
            HttpRequestMessage message = new HttpRequestMessage(HttpMethod.Patch, task.url);
            logger.LogInformation("Pre experiment task to URL {0}", task.url);
            resps_.Add(HttpUtils.client.SendAsync(message));
        }
        await Task.WhenAll(resps_);

        this.channelsToTrim.AddRange(config.streamingConfig.streams);

        // should also iterate over all transaction mark streams and trim them
        foreach (var type in eventualCompletionTransactions)
        {
            var channel = new StringBuilder(nameof(TransactionMark)).Append('_').Append(type.ToString()).ToString();
            this.channelsToTrim.Add(channel);
        }

        await RedisUtils.TrimStreams(redisConnection, channelsToTrim);

        base.PreExperiment();

    }

    protected override async void PostRunTasks(int runIdx)
    {
        // trim first to avoid receiving events after the post run task
        await RedisUtils.TrimStreams(redisConnection, channelsToTrim);

        // reset data in microservices - post run
        if (runIdx < this.config.runs.Count - 1)
        {
            logger.LogInformation("Post run tasks started");
            var responses = new List<Task<HttpResponseMessage>>();
            List<PostRunTask> postRunTasks;
            // must call the cleanup if next run changes number of products
            if (config.runs[runIdx + 1].numProducts != config.runs[runIdx].numProducts)
            {
                logger.LogInformation("Next run changes the number of products.");
                postRunTasks = config.postExperimentTasks;
            }
            else
            {
                logger.LogInformation("Next run does not change the number of products.");
                postRunTasks = config.postRunTasks;
            }
            foreach (var task in postRunTasks)
            {
                HttpRequestMessage message = new HttpRequestMessage(HttpMethod.Patch, task.url);
                logger.LogInformation("Post run task to Microservice {0} URL {1}", task.name, task.url);
                responses.Add(HttpUtils.client.SendAsync(message));
            }
            await Task.WhenAll(responses);
            logger.LogInformation("Post run tasks finished");
        }

    }

    protected override MetricManager SetUpMetricManager(int runIdx)
    {
        // logger.LogInformation("Setting up KafkaMetricManager with {0} sellers and {1} customers.", this.numSellers, this.config.numCustomers);
        this.metricManager.SetUp(this.numSellers, this.config.numCustomers);
        return this.metricManager;
    }
}
