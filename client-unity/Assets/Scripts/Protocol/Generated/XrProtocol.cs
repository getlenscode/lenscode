namespace Aura.Protocol
{
    using System;
    using System.Collections.Generic;

    using System.Globalization;
    using Newtonsoft.Json;
    using Newtonsoft.Json.Converters;

    public partial class XrRequest
    {
        [JsonProperty("clientInfo", NullValueHandling = NullValueHandling.Ignore)]
        public ClientInfo? ClientInfo { get; set; }

        [JsonProperty("deviceId", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? DeviceId { get; set; }

        [JsonProperty("deviceToken", NullValueHandling = NullValueHandling.Ignore)]
        public string? DeviceToken { get; set; }

        [JsonProperty("id")]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string Id { get; set; }

        [JsonProperty("protocolVersion", NullValueHandling = NullValueHandling.Ignore)]
        public double? ProtocolVersion { get; set; }

        [JsonProperty("type")]
        public XrRequestType Type { get; set; }

        [JsonProperty("deviceName", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? DeviceName { get; set; }

        [JsonProperty("pairingCode", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(FluffyMinMaxLengthCheckConverter))]
        public string? PairingCode { get; set; }

        [JsonProperty("topics", NullValueHandling = NullValueHandling.Ignore)]
        public TopicElement[]? Topics { get; set; }

        [JsonProperty("machineId", NullValueHandling = NullValueHandling.Ignore)]
        public string? MachineId { get; set; }

        [JsonProperty("path", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? Path { get; set; }

        [JsonProperty("workspaceId", NullValueHandling = NullValueHandling.Ignore)]
        public string? WorkspaceId { get; set; }

        [JsonProperty("context", NullValueHandling = NullValueHandling.Ignore)]
        public Context? Context { get; set; }

        [JsonProperty("limit", NullValueHandling = NullValueHandling.Ignore)]
        public long? Limit { get; set; }

        [JsonProperty("query", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? Query { get; set; }

        [JsonProperty("cwd", NullValueHandling = NullValueHandling.Ignore)]
        public string? Cwd { get; set; }

        [JsonProperty("name", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? Name { get; set; }

        [JsonProperty("paneId", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? PaneId { get; set; }

        [JsonProperty("format", NullValueHandling = NullValueHandling.Ignore)]
        public Format? Format { get; set; }

        [JsonProperty("lines", NullValueHandling = NullValueHandling.Ignore)]
        public long? Lines { get; set; }

        [JsonProperty("source", NullValueHandling = NullValueHandling.Ignore)]
        public Source? Source { get; set; }

        [JsonProperty("text", NullValueHandling = NullValueHandling.Ignore)]
        public string? Text { get; set; }

        [JsonProperty("confirmed", NullValueHandling = NullValueHandling.Ignore)]
        public bool? Confirmed { get; set; }

        [JsonProperty("onDirty", NullValueHandling = NullValueHandling.Ignore)]
        public OnDirty? OnDirty { get; set; }

        [JsonProperty("target", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? Target { get; set; }

        [JsonProperty("request", NullValueHandling = NullValueHandling.Ignore)]
        public Request? Request { get; set; }
    }

    public partial class ClientInfo
    {
        [JsonProperty("app")]
        public string App { get; set; }

        [JsonProperty("platform")]
        public string Platform { get; set; }

        [JsonProperty("version")]
        public string Version { get; set; }
    }

    public partial class Context
    {
        [JsonProperty("activePanel", NullValueHandling = NullValueHandling.Ignore)]
        public string? ActivePanel { get; set; }

        [JsonProperty("gazeTarget", NullValueHandling = NullValueHandling.Ignore)]
        public string? GazeTarget { get; set; }

        [JsonProperty("recentFiles", NullValueHandling = NullValueHandling.Ignore)]
        public string[]? RecentFiles { get; set; }
    }

    public partial class Request
    {
        [JsonProperty("id")]
        public string Id { get; set; }

        [JsonProperty("method")]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string Method { get; set; }

        [JsonProperty("params")]
        public Dictionary<string, object> Params { get; set; }
    }

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
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? Event { get; set; }

        [JsonProperty("seq", NullValueHandling = NullValueHandling.Ignore)]
        public long? Seq { get; set; }

        [JsonProperty("topic", NullValueHandling = NullValueHandling.Ignore)]
        public XrServerMessageTopic? Topic { get; set; }

        [JsonProperty("type", NullValueHandling = NullValueHandling.Ignore)]
        public XrServerMessageType? Type { get; set; }
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

    public enum Format { Ansi, Text };

    public enum OnDirty { Abort, Stash, Worktree };

    public enum Source { Recent, RecentUnwrapped, Visible };

    public enum TopicElement { Files, Git, Herdr, Machines };

    public enum XrRequestType { DirList, EventsSubscribe, FileOpen, GitBranchSwitch, GitStatus, Hello, HerdrRequest, PairBegin, PairComplete, SearchFiles, SnapshotRequest, TerminalClose, TerminalCreate, TerminalFocus, TerminalInput, TerminalInterrupt, TerminalList, TerminalProcessInfo, TerminalRead, TerminalRename };

    public enum Code { Conflict, DangerousOpUnconfirmed, HerdrError, HerdrUnavailable, Internal, InvalidRequest, NotFound, PairingRequired, Unauthorized };

    public enum XrServerMessageTopic { Connection, Files, Git, Herdr, Machines };

    public enum XrServerMessageType { Event };

    internal static class Converter
    {
        public static readonly JsonSerializerSettings Settings = new JsonSerializerSettings
        {
            MetadataPropertyHandling = MetadataPropertyHandling.Ignore,
            DateParseHandling = DateParseHandling.None,
            Converters =
            {
                FormatConverter.Singleton,
                OnDirtyConverter.Singleton,
                SourceConverter.Singleton,
                TopicElementConverter.Singleton,
                XrRequestTypeConverter.Singleton,
                CodeConverter.Singleton,
                XrServerMessageTopicConverter.Singleton,
                XrServerMessageTypeConverter.Singleton,
                new IsoDateTimeConverter { DateTimeStyles = DateTimeStyles.AssumeUniversal }
            },
        };
    }

    internal class PurpleMinMaxLengthCheckConverter : JsonConverter
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

        public static readonly PurpleMinMaxLengthCheckConverter Singleton = new PurpleMinMaxLengthCheckConverter();
    }

    internal class FormatConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(Format) || t == typeof(Format?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "ansi":
                    return Format.Ansi;
                case "text":
                    return Format.Text;
            }
            throw new Exception("Cannot unmarshal type Format");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (Format)untypedValue;
            switch (value)
            {
                case Format.Ansi:
                    serializer.Serialize(writer, "ansi");
                    return;
                case Format.Text:
                    serializer.Serialize(writer, "text");
                    return;
            }
            throw new Exception("Cannot marshal type Format");
        }

        public static readonly FormatConverter Singleton = new FormatConverter();
    }

    internal class OnDirtyConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(OnDirty) || t == typeof(OnDirty?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "abort":
                    return OnDirty.Abort;
                case "stash":
                    return OnDirty.Stash;
                case "worktree":
                    return OnDirty.Worktree;
            }
            throw new Exception("Cannot unmarshal type OnDirty");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (OnDirty)untypedValue;
            switch (value)
            {
                case OnDirty.Abort:
                    serializer.Serialize(writer, "abort");
                    return;
                case OnDirty.Stash:
                    serializer.Serialize(writer, "stash");
                    return;
                case OnDirty.Worktree:
                    serializer.Serialize(writer, "worktree");
                    return;
            }
            throw new Exception("Cannot marshal type OnDirty");
        }

        public static readonly OnDirtyConverter Singleton = new OnDirtyConverter();
    }

    internal class FluffyMinMaxLengthCheckConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(string);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            var value = serializer.Deserialize<string>(reader);
            if (value.Length >= 6 && value.Length <= 10)
            {
                return value;
            }
            throw new Exception("Cannot unmarshal type string");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            var value = (string)untypedValue;
            if (value.Length >= 6 && value.Length <= 10)
            {
                serializer.Serialize(writer, value);
                return;
            }
            throw new Exception("Cannot marshal type string");
        }

        public static readonly FluffyMinMaxLengthCheckConverter Singleton = new FluffyMinMaxLengthCheckConverter();
    }

    internal class SourceConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(Source) || t == typeof(Source?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "recent":
                    return Source.Recent;
                case "recent_unwrapped":
                    return Source.RecentUnwrapped;
                case "visible":
                    return Source.Visible;
            }
            throw new Exception("Cannot unmarshal type Source");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (Source)untypedValue;
            switch (value)
            {
                case Source.Recent:
                    serializer.Serialize(writer, "recent");
                    return;
                case Source.RecentUnwrapped:
                    serializer.Serialize(writer, "recent_unwrapped");
                    return;
                case Source.Visible:
                    serializer.Serialize(writer, "visible");
                    return;
            }
            throw new Exception("Cannot marshal type Source");
        }

        public static readonly SourceConverter Singleton = new SourceConverter();
    }

    internal class TopicElementConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(TopicElement) || t == typeof(TopicElement?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "files":
                    return TopicElement.Files;
                case "git":
                    return TopicElement.Git;
                case "herdr":
                    return TopicElement.Herdr;
                case "machines":
                    return TopicElement.Machines;
            }
            throw new Exception("Cannot unmarshal type TopicElement");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (TopicElement)untypedValue;
            switch (value)
            {
                case TopicElement.Files:
                    serializer.Serialize(writer, "files");
                    return;
                case TopicElement.Git:
                    serializer.Serialize(writer, "git");
                    return;
                case TopicElement.Herdr:
                    serializer.Serialize(writer, "herdr");
                    return;
                case TopicElement.Machines:
                    serializer.Serialize(writer, "machines");
                    return;
            }
            throw new Exception("Cannot marshal type TopicElement");
        }

        public static readonly TopicElementConverter Singleton = new TopicElementConverter();
    }

    internal class XrRequestTypeConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(XrRequestType) || t == typeof(XrRequestType?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "dir.list":
                    return XrRequestType.DirList;
                case "events.subscribe":
                    return XrRequestType.EventsSubscribe;
                case "file.open":
                    return XrRequestType.FileOpen;
                case "git.branch.switch":
                    return XrRequestType.GitBranchSwitch;
                case "git.status":
                    return XrRequestType.GitStatus;
                case "hello":
                    return XrRequestType.Hello;
                case "herdr.request":
                    return XrRequestType.HerdrRequest;
                case "pair.begin":
                    return XrRequestType.PairBegin;
                case "pair.complete":
                    return XrRequestType.PairComplete;
                case "search.files":
                    return XrRequestType.SearchFiles;
                case "snapshot.request":
                    return XrRequestType.SnapshotRequest;
                case "terminal.close":
                    return XrRequestType.TerminalClose;
                case "terminal.create":
                    return XrRequestType.TerminalCreate;
                case "terminal.focus":
                    return XrRequestType.TerminalFocus;
                case "terminal.input":
                    return XrRequestType.TerminalInput;
                case "terminal.interrupt":
                    return XrRequestType.TerminalInterrupt;
                case "terminal.list":
                    return XrRequestType.TerminalList;
                case "terminal.process_info":
                    return XrRequestType.TerminalProcessInfo;
                case "terminal.read":
                    return XrRequestType.TerminalRead;
                case "terminal.rename":
                    return XrRequestType.TerminalRename;
            }
            throw new Exception("Cannot unmarshal type XrRequestType");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (XrRequestType)untypedValue;
            switch (value)
            {
                case XrRequestType.DirList:
                    serializer.Serialize(writer, "dir.list");
                    return;
                case XrRequestType.EventsSubscribe:
                    serializer.Serialize(writer, "events.subscribe");
                    return;
                case XrRequestType.FileOpen:
                    serializer.Serialize(writer, "file.open");
                    return;
                case XrRequestType.GitBranchSwitch:
                    serializer.Serialize(writer, "git.branch.switch");
                    return;
                case XrRequestType.GitStatus:
                    serializer.Serialize(writer, "git.status");
                    return;
                case XrRequestType.Hello:
                    serializer.Serialize(writer, "hello");
                    return;
                case XrRequestType.HerdrRequest:
                    serializer.Serialize(writer, "herdr.request");
                    return;
                case XrRequestType.PairBegin:
                    serializer.Serialize(writer, "pair.begin");
                    return;
                case XrRequestType.PairComplete:
                    serializer.Serialize(writer, "pair.complete");
                    return;
                case XrRequestType.SearchFiles:
                    serializer.Serialize(writer, "search.files");
                    return;
                case XrRequestType.SnapshotRequest:
                    serializer.Serialize(writer, "snapshot.request");
                    return;
                case XrRequestType.TerminalClose:
                    serializer.Serialize(writer, "terminal.close");
                    return;
                case XrRequestType.TerminalCreate:
                    serializer.Serialize(writer, "terminal.create");
                    return;
                case XrRequestType.TerminalFocus:
                    serializer.Serialize(writer, "terminal.focus");
                    return;
                case XrRequestType.TerminalInput:
                    serializer.Serialize(writer, "terminal.input");
                    return;
                case XrRequestType.TerminalInterrupt:
                    serializer.Serialize(writer, "terminal.interrupt");
                    return;
                case XrRequestType.TerminalList:
                    serializer.Serialize(writer, "terminal.list");
                    return;
                case XrRequestType.TerminalProcessInfo:
                    serializer.Serialize(writer, "terminal.process_info");
                    return;
                case XrRequestType.TerminalRead:
                    serializer.Serialize(writer, "terminal.read");
                    return;
                case XrRequestType.TerminalRename:
                    serializer.Serialize(writer, "terminal.rename");
                    return;
            }
            throw new Exception("Cannot marshal type XrRequestType");
        }

        public static readonly XrRequestTypeConverter Singleton = new XrRequestTypeConverter();
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

    internal class XrServerMessageTopicConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(XrServerMessageTopic) || t == typeof(XrServerMessageTopic?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
                case "connection":
                    return XrServerMessageTopic.Connection;
                case "files":
                    return XrServerMessageTopic.Files;
                case "git":
                    return XrServerMessageTopic.Git;
                case "herdr":
                    return XrServerMessageTopic.Herdr;
                case "machines":
                    return XrServerMessageTopic.Machines;
            }
            throw new Exception("Cannot unmarshal type XrServerMessageTopic");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (XrServerMessageTopic)untypedValue;
            switch (value)
            {
                case XrServerMessageTopic.Connection:
                    serializer.Serialize(writer, "connection");
                    return;
                case XrServerMessageTopic.Files:
                    serializer.Serialize(writer, "files");
                    return;
                case XrServerMessageTopic.Git:
                    serializer.Serialize(writer, "git");
                    return;
                case XrServerMessageTopic.Herdr:
                    serializer.Serialize(writer, "herdr");
                    return;
                case XrServerMessageTopic.Machines:
                    serializer.Serialize(writer, "machines");
                    return;
            }
            throw new Exception("Cannot marshal type XrServerMessageTopic");
        }

        public static readonly XrServerMessageTopicConverter Singleton = new XrServerMessageTopicConverter();
    }

    internal class XrServerMessageTypeConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(XrServerMessageType) || t == typeof(XrServerMessageType?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            if (value == "event")
            {
                return XrServerMessageType.Event;
            }
            throw new Exception("Cannot unmarshal type XrServerMessageType");
        }

        public override void WriteJson(JsonWriter writer, object untypedValue, JsonSerializer serializer)
        {
            if (untypedValue == null)
            {
                serializer.Serialize(writer, null);
                return;
            }
            var value = (XrServerMessageType)untypedValue;
            if (value == XrServerMessageType.Event)
            {
                serializer.Serialize(writer, "event");
                return;
            }
            throw new Exception("Cannot marshal type XrServerMessageType");
        }

        public static readonly XrServerMessageTypeConverter Singleton = new XrServerMessageTypeConverter();
    }
}
