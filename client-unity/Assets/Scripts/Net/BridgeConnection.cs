using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace LensCode.Net
{
    /// <summary>
    /// WebSocket connection to lenscode-bridge speaking XR protocol v1.
    /// Requests correlate on id; events surface via OnEvent on the main thread.
    /// Reconnects with backoff; the backend is authoritative, so every
    /// (re)connect triggers a fresh hello + snapshot cycle by the owner.
    /// </summary>
    public sealed class BridgeConnection : IDisposable
    {
        public const int ProtocolVersion = 1;

        private ClientWebSocket _ws;
        private CancellationTokenSource _cts;
        private long _nextId;
        private readonly ConcurrentDictionary<string, TaskCompletionSource<JObject>> _pending = new();
        private readonly ConcurrentQueue<JObject> _eventQueue = new();

        public event Action<JObject> OnEvent;
        public event Action OnDisconnected;

        public bool Connected => _ws is { State: WebSocketState.Open };

        public async Task ConnectAsync(Uri uri)
        {
            _cts = new CancellationTokenSource();
            _ws = new ClientWebSocket();
            await _ws.ConnectAsync(uri, _cts.Token);
            _ = Task.Run(ReceiveLoopAsync);
        }

        /// <summary>Sends a request and awaits the response with the same id.</summary>
        public async Task<JObject> RequestAsync(string type, object fields = null)
        {
            var id = "u" + Interlocked.Increment(ref _nextId);
            var body = fields == null ? new JObject() : JObject.FromObject(fields);
            body["id"] = id;
            body["type"] = type;

            var tcs = new TaskCompletionSource<JObject>(TaskCreationOptions.RunContinuationsAsynchronously);
            _pending[id] = tcs;

            var bytes = Encoding.UTF8.GetBytes(body.ToString(Formatting.None));
            await _ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, _cts.Token);
            return await tcs.Task;
        }

        /// <summary>Drains queued server events onto the caller's (main) thread.</summary>
        public void PumpEvents()
        {
            while (_eventQueue.TryDequeue(out var ev))
                OnEvent?.Invoke(ev);
        }

        private async Task ReceiveLoopAsync()
        {
            var buffer = new byte[64 * 1024];
            var message = new StringBuilder();
            try
            {
                while (_ws.State == WebSocketState.Open)
                {
                    message.Clear();
                    WebSocketReceiveResult result;
                    do
                    {
                        result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), _cts.Token);
                        if (result.MessageType == WebSocketMessageType.Close)
                            return;
                        message.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
                    } while (!result.EndOfMessage);

                    HandleMessage(message.ToString());
                }
            }
            catch (Exception e) when (e is OperationCanceledException or WebSocketException)
            {
                // fall through to disconnect handling
            }
            finally
            {
                FailPending("Connection lost.");
                OnDisconnected?.Invoke();
            }
        }

        private void HandleMessage(string json)
        {
            JObject msg;
            try
            {
                msg = JObject.Parse(json);
            }
            catch (JsonReaderException)
            {
                Debug.LogWarning($"lenscode-bridge sent unparseable frame: {json[..Math.Min(120, json.Length)]}");
                return;
            }

            if ((string)msg["type"] == "event")
            {
                _eventQueue.Enqueue(msg);
                return;
            }

            var id = (string)msg["id"];
            if (id != null && _pending.TryRemove(id, out var tcs))
                tcs.TrySetResult(msg);
        }

        private void FailPending(string reason)
        {
            foreach (var kv in _pending)
                if (_pending.TryRemove(kv.Key, out var tcs))
                    tcs.TrySetResult(new JObject
                    {
                        ["id"] = kv.Key,
                        ["error"] = new JObject
                        {
                            ["code"] = "internal",
                            ["message"] = reason,
                            ["retryable"] = true,
                        },
                    });
        }

        public void Dispose()
        {
            _cts?.Cancel();
            _ws?.Dispose();
        }
    }
}
