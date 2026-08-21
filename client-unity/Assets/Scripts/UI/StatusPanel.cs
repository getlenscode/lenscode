using Aura.Net;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.UIElements;

namespace Aura.UI
{
    /// <summary>
    /// World-space glass panel showing bridge/Herdr connection state and a live
    /// count of workspaces, panes, and agents from the authoritative snapshot.
    /// Renders through a UI Toolkit World Space UIDocument (PrimaryPanel.uxml).
    /// </summary>
    public sealed class StatusPanel : MonoBehaviour
    {
        [SerializeField] private AuraClient client;
        [SerializeField] private UIDocument uiDocument;

        // UI Toolkit's rich-text <color> tags do not actually recolor spans in
        // this runtime — Label.resolvedStyle.color wins regardless of markup —
        // so headline/detail are separate Labels colored directly instead.
        private static readonly Color TextColor = new Color(0.933f, 0.969f, 1f);
        private static readonly Color MutedColor = new Color(0.6f, 0.671f, 0.753f);
        private static readonly Color GoodColor = new Color(0.349f, 0.898f, 0.549f);
        private static readonly Color WarnColor = new Color(1f, 0.820f, 0.4f);
        private static readonly Color BadColor = new Color(1f, 0.498f, 0.584f);

        private Label _headline;
        private Label _detail;

        private void Awake()
        {
            if (uiDocument == null) uiDocument = GetComponentInChildren<UIDocument>();
            var root = uiDocument.rootVisualElement;
            _headline = root.Q<Label>("StatusHeadline");
            _detail = root.Q<Label>("StatusDetail");
        }

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
            if (connected) return;
            _headline.text = "Connection lost.";
            _headline.style.color = WarnColor;
            _detail.text = "Agents and terminals may still be running.\nReconnecting…";
            _detail.style.color = MutedColor;
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

            _headline.text = "AURA WORKSPACE";
            _headline.style.color = TextColor;
            _headline.style.unityFontStyleAndWeight = FontStyle.Bold;

            _detail.text =
                $"bridge ● connected   herdr {(client.HerdrConnected ? "● connected" : "○ offline")}\n" +
                $"workspaces {workspaces} · panes {panes} · agents {agents}\n" +
                $"last event: {_lastEvent}";
            _detail.style.color = client.HerdrConnected ? GoodColor : BadColor;
        }
    }
}
