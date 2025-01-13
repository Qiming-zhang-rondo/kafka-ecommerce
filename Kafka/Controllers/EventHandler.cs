using Common.Streaming;
using Common.Workload;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Threading.Tasks;
using Confluent.Kafka;
using Common.Infra;
using Microsoft.Extensions.Logging;

namespace Kafka.Controllers;

public class EventHandler
{
    private const string ProductUpdateTopic = "TransactionMark_UPDATE_PRODUCT";
    private const string PriceUpdateTopic = "TransactionMark_PRICE_UPDATE";
    private const string CheckoutTopic = "TransactionMark_CUSTOMER_SESSION";

    protected static readonly ILogger logger = LoggerProxy.GetInstance("Eventhandler");
    private readonly IConsumer<Ignore, string> _consumer;

    public EventHandler(IConsumer<Ignore, string> consumer)
    {
        _consumer = consumer;
    }

    public void Initialize()
    {
        // 只启动一个线程，监听所有的 topic
        Task.Run(() => StartListening());
    }

    private void StartListening()
    {
        // 订阅所有的 topic
        _consumer.Subscribe(new[] { ProductUpdateTopic, PriceUpdateTopic, CheckoutTopic });
        logger.LogInformation("Started listening to multiple topics.");

        Task.Run(() =>
        {
            while (true)
            {
                try
                {
                    var consumeResult = _consumer.Consume();
                    // logger.LogInformation($"Message received from topic {consumeResult.Topic}: {consumeResult.Message.Value}");

                    // 根据消息的 topic 调用不同的处理方法
                    if (consumeResult.Topic == ProductUpdateTopic)
                    {
                        ProcessProductUpdateMark(consumeResult.Message.Value).Wait();
                    }
                    else if (consumeResult.Topic == PriceUpdateTopic)
                    {
                        ProcessPriceUpdateMark(consumeResult.Message.Value).Wait();
                    }
                    else if (consumeResult.Topic == CheckoutTopic)
                    {
                        ProcessCheckoutMark(consumeResult.Message.Value).Wait();
                    }
                    else
                    {
                        logger.LogError($"Unexpected topic received: {consumeResult.Topic}");
                    }
                }
                catch (ConsumeException ex)
                {
                    logger.LogError($"Kafka consume error: {ex.Error.Reason}");
                }
                catch (Exception ex)
                {
                    logger.LogError($"Unexpected error in Kafka consumer: {ex.Message}");
                }
            }
        });
    }

    private async Task ProcessProductUpdateMark(string message)
    {
        try
        {
            var options = new JsonSerializerOptions
            {
                Converters = { new TransactionTypeConverter(), new MarkStatusConverter() }
            };

            // logger.LogInformation("Attempting to deserialize message to TransactionMark.");
            var productUpdateMark = JsonSerializer.Deserialize<TransactionMark>(message, options);

            if (productUpdateMark != null)
            {
                // logger.LogInformation("Deserialization successful. ProductUpdateMark: {0}", productUpdateMark);

                await Shared.ResultQueue.Writer.WriteAsync(Shared.ITEM);
                // logger.LogInformation("Item written to ResultQueue.");

                if (productUpdateMark.status == MarkStatus.SUCCESS)
                {
                    // logger.LogInformation("Status is SUCCESS. Writing to ProductUpdateOutputs.");
                    await Shared.ProductUpdateOutputs.Writer.WriteAsync(new(productUpdateMark.tid, DateTime.UtcNow));
                }
                else
                {
                    // logger.LogInformation("Status is not SUCCESS. Writing to PoisonProductUpdateOutputs.");
                    await Shared.PoisonProductUpdateOutputs.Writer.WriteAsync(productUpdateMark);
                }
            }
            else
            {
                // logger.LogWarning("Deserialization failed. productUpdateMark is null.");
            }
        }
        catch (JsonException ex)
        {
            logger.LogError($"Error in ProcessProductUpdateMark: {ex.Message}");
            logger.LogError($"Failed to deserialize JSON: {message}");
        }
        catch (Exception ex)
        {
            logger.LogError($"Error in ProcessProductUpdateMark: {ex.Message}");
        }
    }

    private async Task ProcessPriceUpdateMark(string message)
    {
        try
        {
            var options = new JsonSerializerOptions
            {
                Converters = { new TransactionTypeConverter(), new MarkStatusConverter() }
            };

            var priceUpdateMark = JsonSerializer.Deserialize<TransactionMark>(message, options);

            if (priceUpdateMark != null)
            {
                await Shared.ResultQueue.Writer.WriteAsync(Shared.ITEM);
                if (priceUpdateMark.status == MarkStatus.SUCCESS)
                {
                    await Shared.PriceUpdateOutputs.Writer.WriteAsync(new(priceUpdateMark.tid, DateTime.UtcNow));
                }
                else
                {
                    await Shared.PoisonPriceUpdateOutputs.Writer.WriteAsync(priceUpdateMark);
                }
            }
        }
        catch (Exception ex)
        {
            logger.LogError($"Error in ProcessPriceUpdateMark: {ex.Message}");
            logger.LogError($"Failed to deserialize JSON: {message}");
        }
    }

    private async Task ProcessCheckoutMark(string message)
    {
        try
        {
            var options = new JsonSerializerOptions
            {
                Converters = { new TransactionTypeConverter(), new MarkStatusConverter() }
            };
            // logger.LogInformation("Attempting to deserialize message to TransactionMark.");
            var checkoutMark = JsonSerializer.Deserialize<TransactionMark>(message, options);

            if (checkoutMark != null)
            {
                // logger.LogInformation("Deserialization successful. ProductUpdateMark: {0}", checkoutMark);
                await Shared.ResultQueue.Writer.WriteAsync(Shared.ITEM);
                // logger.LogInformation("Item written to ResultQueue.");
                if (checkoutMark.status == MarkStatus.SUCCESS)
                {
                    // await Shared.PoisonCheckoutOutputs.Writer.WriteAsync(checkoutMark);
                    await Shared.CheckoutOutputs.Writer.WriteAsync(new(checkoutMark.tid, DateTime.UtcNow));
                    // logger.LogInformation("Writing to CheckoutOutputs. Tid: {Tid}, Timestamp: {Timestamp}", 
                    //   checkoutMark.tid, DateTime.UtcNow);
                }
                else
                {
                    await Shared.PoisonCheckoutOutputs.Writer.WriteAsync(checkoutMark);
                }
            }
        }
        catch (Exception ex)
        {
            logger.LogError($"Error in ProcessCheckoutMark: {ex.Message}");
        }
    }
}
