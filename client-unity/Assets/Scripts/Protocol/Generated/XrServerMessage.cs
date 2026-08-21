namespace Aura.Protocol
{
    using System;
    using System.Collections.Generic;

    using System.Globalization;
    using Newtonsoft.Json;
    using Newtonsoft.Json.Converters;

    public partial class XrServerMessage
    {
        [JsonProperty("error", NullValueHandling = NullValueHandling.Ignore)]
        public Error? Error { get; set; }

        [JsonProperty("id", NullValueHandling = NullValueHandling.Ignore)]
        public string? Id { get; set; }

        [JsonProperty("result")]
        public object? Result { get; set; }

        [JsonProperty("data")]
        public object? Data { get; set; }

        [JsonProperty("event", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(MinMaxLengthCheckConverter))]
        public string? Event { get; set; }

        [JsonProperty("seq", NullValueHandling = NullValueHandling.Ignore)]
        public long? Seq { get; set; }

        [JsonProperty("topic", NullValueHandling = NullValueHandling.Ignore)]
        public Topic? Topic { get; set; }

        [JsonProperty("type", NullValueHandling = NullValueHandling.Ignore)]
        public TypeEnum? Type { get; set; }
    }

    public partial class Error
    {
        [JsonProperty("code")]
        public Code Code { get; set; }

        [JsonProperty("detail", NullValueHandling = NullValueHandling.Ignore)]
        public string? Detail { get; set; }

        [JsonProperty("message")]
        public string Message { get; set; }

        [JsonProperty("retryable")]
        public bool Retryable { get; set; }
    }

    public enum Code { Conflict, DangerousOpUnconfirmed, HerdrError, HerdrUnavailable, Internal, InvalidRequest, NotFound, PairingRequired, Unauthorized };

    public enum Topic { Connection, Files, Git, Herdr, Machines };

    public enum TypeEnum { Event };

    internal static class Converter
    {
        public static readonly JsonSerializerSettings Settings = new JsonSerializerSettings
        {
            MetadataPropertyHandling = MetadataPropertyHandling.Ignore,
            DateParseHandling = DateParseHandling.None,
            Converters =
            {
                CodeConverter.Singleton,
                TopicConverter.Singleton,
                TypeEnumConverter.Singleton,
                new IsoDateTimeConverter { DateTimeStyles = DateTimeStyles.AssumeUniversal }
            },
        };
    }

    internal class CodeConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(Code) || t == typeof(Code?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "conflict":
                    return Code.Conflict;
                case "dangerous_op_unconfirmed":
                    return Code.DangerousOpUnconfirmed;
                case "herdr_error":
                    return Code.HerdrError;
                case "herdr_unavailable":
                    return Code.HerdrUnavailable;
                case "internal":
                    return Code.Internal;
                case "invalid_request":
                    return Code.InvalidRequest;
                case "not_found":
                    return Code.NotFound;
                case "pairing_required":
                    return Code.PairingRequired;
                case "unauthorized":
                    return Code.Unauthorized;
            }
            throw new Exception("Cannot unmarshal type Code");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (Code)untypedValue;
            switch (value)
            {
                case Code.Conflict:
                    serializer.Serialize(writer, "conflict");
                    return;
                case Code.DangerousOpUnconfirmed:
                    serializer.Serialize(writer, "dangerous_op_unconfirmed");
                    return;
                case Code.HerdrError:
                    serializer.Serialize(writer, "herdr_error");
                    return;
                case Code.HerdrUnavailable:
                    serializer.Serialize(writer, "herdr_unavailable");
                    return;
                case Code.Internal:
                    serializer.Serialize(writer, "internal");
                    return;
                case Code.InvalidRequest:
                    serializer.Serialize(writer, "invalid_request");
                    return;
                case Code.NotFound:
                    serializer.Serialize(writer, "not_found");
                    return;
                case Code.PairingRequired:
                    serializer.Serialize(writer, "pairing_required");
                    return;
                case Code.Unauthorized:
                    serializer.Serialize(writer, "unauthorized");
                    return;
            }
            throw new Exception("Cannot marshal type Code");
        }

        public static readonly CodeConverter Singleton = new CodeConverter();
    }

    internal class MinMaxLengthCheckConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(string);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            var value = serializer.Deserialize<string>(reader);
            if (value.Length >= 1)
            {
                return value;
            }
            throw new Exception("Cannot unmarshal type string");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            var value = (string)untypedValue;
            if (value.Length >= 1)
            {
                serializer.Serialize(writer, value);
                return;
            }
            throw new Exception("Cannot marshal type string");
        }

        public static readonly MinMaxLengthCheckConverter Singleton = new MinMaxLengthCheckConverter();
    }

    internal class TopicConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(Topic) || t == typeof(Topic?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "connection":
                    return Topic.Connection;
                case "files":
                    return Topic.Files;
                case "git":
                    return Topic.Git;
                case "herdr":
                    return Topic.Herdr;
                case "machines":
                    return Topic.Machines;
            }
            throw new Exception("Cannot unmarshal type Topic");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (Topic)untypedValue;
            switch (value)
            {
                case Topic.Connection:
                    serializer.Serialize(writer, "connection");
                    return;
                case Topic.Files:
                    serializer.Serialize(writer, "files");
                    return;
                case Topic.Git:
                    serializer.Serialize(writer, "git");
                    return;
                case Topic.Herdr:
                    serializer.Serialize(writer, "herdr");
                    return;
                case Topic.Machines:
                    serializer.Serialize(writer, "machines");
                    return;
            }
            throw new Exception("Cannot marshal type Topic");
        }

        public static readonly TopicConverter Singleton = new TopicConverter();
    }

    internal class TypeEnumConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(TypeEnum) || t == typeof(TypeEnum?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            if (value == "event")
            {
                return TypeEnum.Event;
            }
            throw new Exception("Cannot unmarshal type TypeEnum");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (TypeEnum)untypedValue;
            if (value == TypeEnum.Event)
            {
                serializer.Serialize(writer, "event");
                return;
            }
            throw new Exception("Cannot marshal type TypeEnum");
        }

        public static readonly TypeEnumConverter Singleton = new TypeEnumConverter();
    }
}
