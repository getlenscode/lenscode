using Aura.Net;
using Newtonsoft.Json.Linq;
using TMPro;
using UnityEngine;

namespace Aura.UI
{
    /// <summary>
    /// M1 placeholder world-space panel: shows bridge/Herdr connection state and a
    /// live count of workspaces, panes, and agents from the authoritative snapshot.
    /// Replaced by the real panel system in M3.
    /// </summary>
    public sealed class StatusPanel : MonoBehaviour
    {
        [SerializeField] private AuraClient client;
        [SerializeField] private TMP_Text statusText;

        private void OnEnable()
        {
            client.SnapshotChanged += OnSnapshot;
            client.HerdrEvent += OnHerdrEvent;
            client.ConnectionStateChanged += OnConnectionState;
        }

        private void OnDisable()
        {
            client.SnapshotChanged -= OnSnapshot;
            client.HerdrEvent -= OnHerdrEvent;
            client.ConnectionStateChanged -= OnConnectionState;
        }

        private void OnConnectionState(bool connected)
        {
            if (!connected)
                statusText.text = "<color=#ffd166>Connection lost.</color>\n" +
                                  "Agents and terminals may still be running.\nReconnecting…";
        }

        private void OnSnapshot(JObject snapshot) => Render();

        private void OnHerdrEvent(JObject ev)
        {
            // M1: any Herdr mutation triggers a resnapshot render request later;
            // for now just show the last event name for liveness.
            _lastEvent = (string)ev["event"];
            Render();
        }

        private string _lastEvent = "—";

        private void Render()
        {
            var herdr = client.Snapshot?["herdr"]?["snapshot"];
            var workspaces = (herdr?["workspaces"] as JArray)?.Count ?? 0;
            var panes = (herdr?["panes"] as JArray)?.Count ?? 0;
            var agents = (herdr?["agents"] as JArray)?.Count ?? 0;

            statusText.text =
                $"<b>AURA WORKSPACE</b>\n" +
                $"bridge <color=#59e58c>● connected</color>   " +
                $"herdr {(client.HerdrConnected ? "<color=#59e58c>● connected</color>" : "<color=#ff7f95>○ offline</color>")}\n" +
                $"workspaces {workspaces} · panes {panes} · agents {agents}\n" +
                $"<size=70%>last event: {_lastEvent}</size>";
        }
    }
}
