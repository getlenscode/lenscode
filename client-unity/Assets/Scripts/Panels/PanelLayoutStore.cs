using System.Collections.Generic;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using UnityEngine;

namespace LensCode.Panels
{
    /// <summary>Serializable snapshot of one panel's pose (spec §52 layout persistence).</summary>
    public sealed class PanelLayoutData
    {
        public string PanelId;
        public Vector3 Position;
        public Quaternion Rotation;
        public bool Pinned;
    }

    /// <summary>
    /// Saves/restores panel layout across sessions. Client-side only, per spec §41
    /// ("workspace layout persistence on the client side where appropriate") —
    /// this never talks to lenscode-bridge.
    /// </summary>
    public static class PanelLayoutStore
    {
        private static string FilePath =>
            Path.Combine(Application.persistentDataPath, "panel-layout.json");

        public static void Save(IEnumerable<LensCodePanel> panels)
        {
            var data = panels
                .Where(p => !string.IsNullOrEmpty(p.PanelId))
                .Select(p => p.CaptureLayout())
                .ToList();
            try
            {
                File.WriteAllText(FilePath, JsonConvert.SerializeObject(data, Formatting.Indented));
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"Could not save panel layout: {e.Message}");
            }
        }

        public static Dictionary<string, PanelLayoutData> Load()
        {
            if (!File.Exists(FilePath)) return new Dictionary<string, PanelLayoutData>();
            try
            {
                var list = JsonConvert.DeserializeObject<List<PanelLayoutData>>(File.ReadAllText(FilePath))
                           ?? new List<PanelLayoutData>();
                return list.ToDictionary(d => d.PanelId, d => d);
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"Could not load panel layout, using defaults: {e.Message}");
                return new Dictionary<string, PanelLayoutData>();
            }
        }

        public static void ClearSaved()
        {
            if (File.Exists(FilePath)) File.Delete(FilePath);
        }
    }
}
