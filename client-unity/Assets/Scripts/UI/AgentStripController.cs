using System.Collections.Generic;
using UnityEngine;

namespace LensCode.UI
{
    /// <summary>
    /// Populates the persistent agent strip (spec §10) from a data source and
    /// surfaces selection for the detail card. Deliberately has no Herdr/bridge
    /// dependency — a Phase 2 controller feeds it via <see cref="SetAgents"/>.
    /// </summary>
    public sealed class AgentStripController : MonoBehaviour
    {
        [Tooltip("Pre-placed strip slots, one per potential agent, left to right.")]
        [SerializeField] private AgentStatusItem[] slots;

        /// <summary>Fired when the user pinches an agent item; carries the agentId.</summary>
        public event System.Action<string> AgentSelected;

        private void OnEnable()
        {
            foreach (var slot in slots)
            {
                if (slot != null) slot.Selected += HandleAgentSelected;
            }
        }

        private void OnDisable()
        {
            foreach (var slot in slots)
            {
                if (slot != null) slot.Selected -= HandleAgentSelected;
            }
        }

        private void HandleAgentSelected(string agentId) => AgentSelected?.Invoke(agentId);

        public void SetAgents(IReadOnlyList<AgentSummary> agents)
        {
            for (var i = 0; i < slots.Length; i++)
            {
                if (slots[i] == null) continue;
                var active = i < agents.Count;
                slots[i].gameObject.SetActive(active);
                if (active) slots[i].SetAgent(agents[i]);
            }
        }
    }
}
