using Common.DataGeneration;
using Common.Experiment;
using Common.Http;
using Common.Infra;
using DuckDB.NET.Data;
using Kafka.Workload;
using Kafka.Infra;
using Kafka.Controllers;
using Confluent.Kafka;

namespace Kafka;

public class Program
{
    public static async Task Main(string[] args)
    {
        Console.WriteLine("Initializing benchmark driver...");
        ExperimentConfig config = ConsoleUtility.BuildExperimentConfig(args);
        Console.WriteLine("Configuration parsed. Starting program...");
        DuckDBConnection connection = null;

        // 创建 Host
        var hostBuilder = Host.CreateDefaultBuilder(args)
            .ConfigureServices(services =>
            {
                // 配置 Kafka 消费者
                services.AddSingleton<IConsumer<Ignore, string>>(provider =>
                {
                    var kafkaConfig = new ConsumerConfig
                    {
                        BootstrapServers = "localhost:9092", // Kafka 地址
                        GroupId = "driver-group",           // 消费者组 ID
                        AutoOffsetReset = AutoOffsetReset.Earliest,
                        EnableAutoCommit = true
                    };
                    return new ConsumerBuilder<Ignore, string>(kafkaConfig).Build();
                });

                // 注册 EventHandler
                services.AddSingleton<Kafka.Controllers.EventHandler>();

                // 注册其他依赖项
                services.AddLogging();
            });

        var host = hostBuilder.Build();

        try
        {
            // 显式初始化 EventHandler
            var eventHandler = host.Services.GetRequiredService<Kafka.Controllers.EventHandler>();
            eventHandler.Initialize(); // 初始化 Kafka 消费逻辑
            Console.WriteLine("EventHandler initialized successfully.");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Failed to initialize EventHandler: {ex.Message}");
            return; // 无法初始化 Kafka 消费，直接退出
        }

        // 主程序逻辑
        try
        {
            while (true)
            {
                Console.WriteLine("\n Select an option: ");
                Console.WriteLine("1 - Generate Data");
                Console.WriteLine("2 - Ingest Data");
                Console.WriteLine("3 - Run Experiment");
                Console.WriteLine("4 - Ingest and Run (2 and 3)");
                Console.WriteLine("5 - Parse New Configuration");
                Console.WriteLine("q - Exit");
                Console.Write("Enter your choice: ");

                string op = Console.ReadLine();

                switch (op)
                {
                    case "1":
                        connection = ConsoleUtility.GenerateData(config);
                        break;

                    case "2":
                        if (connection is null)
                        {
                            if (config.connectionString.Equals("DataSource=:memory:"))
                            {
                                Console.WriteLine("Please generate some data first by selecting option 1.");
                                break;
                            }
                            else
                            {
                                connection = new DuckDBConnection(config.connectionString);
                                connection.Open();
                            }
                        }
                        await CustomIngestionOrchestrator.Run(connection, config.ingestionConfig);
                        GC.Collect();
                        break;

                    case "3":
                        if (connection is null)
                        {
                            if (config.connectionString.Equals("DataSource=:memory:"))
                            {
                                Console.WriteLine("Please generate some data first by selecting option 1.");
                                break;
                            }
                            else
                            {
                                connection = new DuckDBConnection(config.connectionString);
                                connection.Open();
                            }
                        }
                        var expManager = KafkaExperimentManager.BuildKafkaExperimentManager(new CustomHttpClientFactory(), config, connection);
                        await expManager.Run();
                        Console.WriteLine("Experiment finished.");
                        break;

                    case "4":
                        if (connection is null)
                        {
                            if (config.connectionString.Equals("DataSource=:memory:"))
                            {
                                Console.WriteLine("Please generate some data first by selecting option 1.");
                                break;
                            }
                            else
                            {
                                connection = new DuckDBConnection(config.connectionString);
                                connection.Open();
                            }
                        }
                        await CustomIngestionOrchestrator.Run(connection, config.ingestionConfig);
                        Console.WriteLine("Delay after ingest...");
                        await Task.Delay(10000);
                        var expManagerRun = KafkaExperimentManager.BuildKafkaExperimentManager(new CustomHttpClientFactory(), config, connection);
                        await expManagerRun.Run();
                        Console.WriteLine("Experiment finished.");
                        break;

                    case "5":
                        config = ConsoleUtility.BuildExperimentConfig(args);
                        Console.WriteLine("Configuration parsed.");
                        break;

                    case "q":
                        return;

                    default:
                        Console.WriteLine("Invalid input. Please try again.");
                        break;
                }
            }
        }
        catch (Exception e)
        {
            Console.WriteLine($"Exception caught: {e.Message}\nStackTrace:\n{e.StackTrace}");
        }
    }
}
