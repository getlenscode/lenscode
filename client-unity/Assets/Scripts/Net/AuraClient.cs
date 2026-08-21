using System;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace Aura.Net
{
    /// <summary>
    /// Scene-level owner of the bridge connection. Runs the initial-sync sequence
    /// (spec §7): hello → snapshot → subscribe, and resyncs on every reconnect
    /// because backend state wins (spec §58–59).
    /// </summary>
    public sealed class AuraClient : MonoBehaviour
    {
        [SerializeField] private string bridgeUrl = "ws://127.0.0.1:8137";
        [SerializeField] private float reconnectDelaySeconds = 3f;

        private BridgeConnection _conn;
        private bool _reconnecting;

        /// <summary>Latest authoritative snapshot; null until first sync.</summary>
        public JObject Snapshot { get; private set; }
        public bool HerdrConnected { get; private set; }

        public event Action<JObject> SnapshotChanged;
        public event Action<JObject> HerdrEvent;
        public event Action<bool> ConnectionStateChanged;

        private async void Start() => await ConnectAndSyncAsync();

        private void Update() => _conn?.PumpEvents();

        private async Task ConnectAndSyncAsync()
        {
            _conn?.Dispose();
            _conn = new BridgeConnection();
            _conn.OnEvent += HandleEvent;
            _conn.OnDisconnected += HandleDisconnected;

            try
            {
                await _conn.ConnectAsync(new Uri(bridgeUrl));

                var hello = await _conn.RequestAsync("hello", new
                {
                    protocolVersion = BridgeConnection.ProtocolVersion,
                    deviceId = SystemInfo.deviceUniqueIdentifier,
                    clientInfo = new
                    {
                        app = "aura-xr",
                        version = Application.version,
                        platform = Application.platform.ToString(),
                    },
                });
                if (hello["error"] != null)
                    throw new Exception((string)hello["error"]["message"]);

                HerdrConnected = (bool?)hello["result"]?["herdrConnected"] ?? false;

                var snapshot = await _conn.RequestAsync("snapshot.request");
                Snapshot = snapshot["result"] as JObject;
                SnapshotChanged?.Invoke(Snapshot);

                await _conn.RequestAsync("events.subscribe", new { topics = new[] { "herdr" } });
                ConnectionStateChanged?.Invoke(true);
                Debug.Log($"aura: synced (herdrConnected={HerdrConnected})");
            }
            catch (Exception e)
            {
                Debug.LogWarning($"aura: connect failed: {e.Message}");
                HandleDisconnected();
            }
        }

        public Task<JObject> RequestAsync(string type, object fields = null)
            => _conn.RequestAsync(type, fields);

        /// <summary>Sends a native Herdr envelope through the transparent proxy.</summary>
        public Task<JObject> HerdrRequestAsync(string method, object @params)
            => _conn.RequestAsync("herdr.request", new
            {
                request = new { id = Guid.NewGuid().ToString("N"), method, @params },
            });

        private void HandleEvent(JObject ev)
        {
            if ((string)ev["topic"] == "herdr")
                HerdrEvent?.Invoke(ev);
        }

        private async void HandleDisconnected()
        {
            if (_reconnecting || !isActiveAndEnabled) return;
            _reconnecting = true;
            ConnectionStateChanged?.Invoke(false);
            // Spec §59: never assume backend processes stopped; retry and resync.
            await Task.Delay(TimeSpan.FromSeconds(reconnectDelaySeconds));
            _reconnecting = false;
            await ConnectAndSyncAsync();
        }

        private void OnDestroy() => _conn?.Dispose();
    }
}
