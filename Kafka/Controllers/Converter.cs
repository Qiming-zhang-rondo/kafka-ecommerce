using System.Text.Json;
using System.Text.Json.Serialization;
using Common.Streaming;
using Common.Workload;

namespace Kafka.Controllers;

    public class TransactionTypeConverter : JsonConverter<TransactionType>
    {
        public override TransactionType Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            var value = reader.GetString();
            return value switch
            {
                "CUSTOMER_SESSION" => TransactionType.CUSTOMER_SESSION,
                "QUERY_DASHBOARD" => TransactionType.QUERY_DASHBOARD,
                "PRICE_UPDATE" => TransactionType.PRICE_UPDATE,
                "UPDATE_PRODUCT" => TransactionType.UPDATE_PRODUCT,
                "UPDATE_DELIVERY" => TransactionType.UPDATE_DELIVERY,
                "NONE" => TransactionType.NONE,
                _ => throw new JsonException($"Unknown TransactionType value: {value}")
            };
        }

        public override void Write(Utf8JsonWriter writer, TransactionType value, JsonSerializerOptions options)
        {
            writer.WriteStringValue(value.ToString());
        }
    }

    public class MarkStatusConverter : JsonConverter<MarkStatus>
    {
        public override MarkStatus Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            var value = reader.GetString();
            return value switch
            {
                "SUCCESS" => MarkStatus.SUCCESS,
                "ERROR" => MarkStatus.ERROR,
                "ABORT" => MarkStatus.ABORT,
                "NOT_ACCEPTED" => MarkStatus.NOT_ACCEPTED,
                _ => throw new JsonException($"Unknown MarkStatus value: {value}")
            };
        }

        public override void Write(Utf8JsonWriter writer, MarkStatus value, JsonSerializerOptions options)
        {
            writer.WriteStringValue(value.ToString());
        }
    }

