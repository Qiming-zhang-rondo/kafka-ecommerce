using System.Collections.Concurrent;
using System.Reflection;
using Common.Ingestion.Config;
using DuckDB.NET.Data;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Kafka.Infra;

public sealed class CustomIngestionOrchestrator
{
    private static readonly HttpClient client = new HttpClient();

    public static async Task Run(DuckDBConnection connection, IngestionConfig config)
    {
        Console.WriteLine("Starting data ingestion for Kafka...");

        var numThreads = config.concurrencyLevel <= 0 ? Environment.ProcessorCount : config.concurrencyLevel;
        Console.WriteLine($"Ingestion process starting with {numThreads} workers.");

        var command = connection.CreateCommand();
        var tasksToWait = new List<Task>();
        var tuples = new BlockingCollection<(JObject, string Url)>();
        var errors = new BlockingCollection<(string, string)>();

        foreach (var table in config.mapTableToUrl)
        {
            command.CommandText = "select * from " + table.Key + ";";
            var queryResult = command.ExecuteReader();
            tasksToWait.Add(Task.Run(() => Produce(tuples, queryResult, table.Value)));
        }

        await Task.WhenAll(tasksToWait);
        Console.WriteLine("50% - Data loading completed.");
        tasksToWait.Clear();

        for (int i = 0; i < numThreads; i++)
        {
            tasksToWait.Add(Task.Run(() => ConsumeShared(tuples, errors)));
        }

        await Task.WhenAll(tasksToWait);
        Console.WriteLine("Data ingestion finished for Kafka.");

        if (errors.Count > 0)
        {
            Console.WriteLine("Errors encountered during ingestion.");
            foreach (var error in errors)
            {
                Console.WriteLine($"{error.Item1}: {error.Item2}");
            }
        }
    }

    private static void Produce(BlockingCollection<(JObject, string Url)> tuples, DuckDBDataReader queryResult, string Url)
    {
        while (queryResult.Read())
        {
            JObject obj = new JObject();
            for (int ordinal = 0; ordinal < queryResult.FieldCount; ordinal++)
            {
                var column = queryResult.GetName(ordinal);
                var val = queryResult.GetValue(ordinal);
                obj[column] = JToken.FromObject(val);
            }
            // Console.WriteLine(obj.ToString(Newtonsoft.Json.Formatting.Indented));
            tuples.Add((obj, Url));
        }
    }

    private static async Task ConsumeShared(BlockingCollection<(JObject, string Url)> tuples, BlockingCollection<(string, string)> errors)
    {
        while (tuples.TryTake(out (JObject Tuple, string Url) item))
        {
            try
            {
                await SendToKafkaService(item.Tuple, item.Url);
            }
            catch (Exception e)
            {
                errors.Add((item.Url, e.Message));
            }
        }
    }

    private static async Task SendToKafkaService(JObject obj, string url)
    {
        string payload = JsonConvert.SerializeObject(obj);
        HttpContent content = new StringContent(payload, System.Text.Encoding.UTF8, "application/json");
        HttpResponseMessage response = await client.PostAsync(url, content);
        if (!response.IsSuccessStatusCode)
        {
            throw new Exception($"Failed to send data to {url}: {response.StatusCode}");
        }
    }
}
