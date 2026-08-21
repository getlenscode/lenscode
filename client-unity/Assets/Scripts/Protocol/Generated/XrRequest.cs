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
        public TypeEnum Type { get; set; }

        [JsonProperty("deviceName", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(PurpleMinMaxLengthCheckConverter))]
        public string? DeviceName { get; set; }

        [JsonProperty("pairingCode", NullValueHandling = NullValueHandling.Ignore)]
        [JsonConverter(typeof(FluffyMinMaxLengthCheckConverter))]
        public string? PairingCode { get; set; }

        [JsonProperty("topics", NullValueHandling = NullValueHandling.Ignore)]
        public Topic[]? Topics { get; set; }

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

    public enum Format { Ansi, Text };

    public enum OnDirty { Abort, Stash, Worktree };

    public enum Source { Recent, RecentUnwrapped, Visible };

    public enum Topic { Files, Git, Herdr, Machines };

    public enum TypeEnum { DirList, EventsSubscribe, FileOpen, GitBranchSwitch, GitStatus, Hello, HerdrRequest, PairBegin, PairComplete, SearchFiles, SnapshotRequest, TerminalClose, TerminalCreate, TerminalFocus, TerminalInput, TerminalInterrupt, TerminalList, TerminalProcessInfo, TerminalRead, TerminalRename };

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
                TopicConverter.Singleton,
                TypeEnumConverter.Singleton,
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

    internal class TopicConverter : JsonConverter
    {
        public override bool CanConvert(Type t) => t == typeof(Topic) || t == typeof(Topic?);

        public override object ReadJson(JsonReader reader, Type t, object existingValue, JsonSerializer serializer)
        {
            if (reader.TokenType == JsonToken.Null) return null;
            var value = serializer.Deserialize<string>(reader);
            switch (value)
            {
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
            switch (value)
            {
                case "dir.list":
                    return TypeEnum.DirList;
                case "events.subscribe":
                    return TypeEnum.EventsSubscribe;
                case "file.open":
                    return TypeEnum.FileOpen;
                case "git.branch.switch":
                    return TypeEnum.GitBranchSwitch;
                case "git.status":
                    return TypeEnum.GitStatus;
                case "hello":
                    return TypeEnum.Hello;
                case "herdr.request":
                    return TypeEnum.HerdrRequest;
                case "pair.begin":
                    return TypeEnum.PairBegin;
                case "pair.complete":
                    return TypeEnum.PairComplete;
                case "search.files":
                    return TypeEnum.SearchFiles;
                case "snapshot.request":
                    return TypeEnum.SnapshotRequest;
                case "terminal.close":
                    return TypeEnum.TerminalClose;
                case "terminal.create":
                    return TypeEnum.TerminalCreate;
                case "terminal.focus":
                    return TypeEnum.TerminalFocus;
                case "terminal.input":
                    return TypeEnum.TerminalInput;
                case "terminal.interrupt":
                    return TypeEnum.TerminalInterrupt;
                case "terminal.list":
                    return TypeEnum.TerminalList;
                case "terminal.process_info":
                    return TypeEnum.TerminalProcessInfo;
                case "terminal.read":
                    return TypeEnum.TerminalRead;
                case "terminal.rename":
                    return TypeEnum.TerminalRename;
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
            switch (value)
            {
                case TypeEnum.DirList:
                    serializer.Serialize(writer, "dir.list");
                    return;
                case TypeEnum.EventsSubscribe:
                    serializer.Serialize(writer, "events.subscribe");
                    return;
                case TypeEnum.FileOpen:
                    serializer.Serialize(writer, "file.open");
                    return;
                case TypeEnum.GitBranchSwitch:
                    serializer.Serialize(writer, "git.branch.switch");
                    return;
                case TypeEnum.GitStatus:
                    serializer.Serialize(writer, "git.status");
                    return;
                case TypeEnum.Hello:
                    serializer.Serialize(writer, "hello");
                    return;
                case TypeEnum.HerdrRequest:
                    serializer.Serialize(writer, "herdr.request");
                    return;
                case TypeEnum.PairBegin:
                    serializer.Serialize(writer, "pair.begin");
                    return;
                case TypeEnum.PairComplete:
                    serializer.Serialize(writer, "pair.complete");
                    return;
                case TypeEnum.SearchFiles:
                    serializer.Serialize(writer, "search.files");
                    return;
                case TypeEnum.SnapshotRequest:
                    serializer.Serialize(writer, "snapshot.request");
                    return;
                case TypeEnum.TerminalClose:
                    serializer.Serialize(writer, "terminal.close");
                    return;
                case TypeEnum.TerminalCreate:
                    serializer.Serialize(writer, "terminal.create");
                    return;
                case TypeEnum.TerminalFocus:
                    serializer.Serialize(writer, "terminal.focus");
                    return;
                case TypeEnum.TerminalInput:
                    serializer.Serialize(writer, "terminal.input");
                    return;
                case TypeEnum.TerminalInterrupt:
                    serializer.Serialize(writer, "terminal.interrupt");
                    return;
                case TypeEnum.TerminalList:
                    serializer.Serialize(writer, "terminal.list");
                    return;
                case TypeEnum.TerminalProcessInfo:
                    serializer.Serialize(writer, "terminal.process_info");
                    return;
                case TypeEnum.TerminalRead:
                    serializer.Serialize(writer, "terminal.read");
                    return;
                case TypeEnum.TerminalRename:
                    serializer.Serialize(writer, "terminal.rename");
                    return;
            }
            throw new Exception("Cannot marshal type TypeEnum");
        }

        public static readonly TypeEnumConverter Singleton = new TypeEnumConverter();
    }
}
