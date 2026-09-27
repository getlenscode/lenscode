using System.Linq;
using UnityEngine;

namespace LensCode.Panels
{
    /// <summary>
    /// Scene-level owner of panel layout persistence: restores saved poses at
    /// startup and saves on pause/quit so the workspace survives an app restart
    /// (spec §52 "Panel layouts SHOULD persist").
    /// </summary>
    public sealed class WorkspaceLayoutController : MonoBehaviour
    {
        [SerializeField] private LensCodePanel[] panels;

        private void Awake()
        {
            if (panels == null || panels.Length == 0)
                panels = FindObjectsByType<LensCodePanel>(FindObjectsSortMode.None);
        }

        private void Start()
        {
            var saved = PanelLayoutStore.Load();
            foreach (var panel in panels)
            {
                if (saved.TryGetValue(panel.PanelId, out var data)) panel.ApplyLayout(data);
            }
        }

        public void ResetAllToDefault()
        {
            foreach (var panel in panels) panel.ResetLayout();
            PanelLayoutStore.ClearSaved();
        }

        private void OnApplicationPause(bool paused)
        {
            if (paused) Save();
        }

        private void OnApplicationQuit() => Save();

        private void Save() => PanelLayoutStore.Save(panels.Where(p => p != null));
    }
}
