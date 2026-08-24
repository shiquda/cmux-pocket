use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

fn default_turn_complete() -> String {
    "turn-complete".to_string()
}

/// Agent turn completion payload sent to Android clients under `agent.session.completed` event.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AgentSessionCompleted {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub event_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    pub surface_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_kind: Option<String>,
    #[serde(default = "default_turn_complete")]
    pub category: String,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl AgentSessionCompleted {
    pub fn new(surface_id: impl Into<String>) -> Self {
        Self {
            event_id: None,
            workspace_id: None,
            surface_id: surface_id.into(),
            agent_kind: None,
            category: default_turn_complete(),
            extra: Map::new(),
        }
    }

    pub fn with_details(
        surface_id: impl Into<String>,
        event_id: Option<String>,
        workspace_id: Option<String>,
        agent_kind: Option<String>,
    ) -> Self {
        Self {
            event_id,
            workspace_id,
            surface_id: surface_id.into(),
            agent_kind,
            category: default_turn_complete(),
            extra: Map::new(),
        }
    }
}

/// Normalizes cmux raw event JSON into an `AgentSessionCompleted` payload.
/// Matches Python's `parse_agent_completion_event(event)`.
pub fn parse_agent_completion_event(event: &Value) -> Option<AgentSessionCompleted> {
    let event_obj = event.as_object()?;

    // Must be a cmux event frame
    if event_obj.get("type").and_then(Value::as_str) != Some("event") {
        return None;
    }

    let payload = match event_obj.get("payload") {
        Some(Value::Object(map)) => Some(map),
        None => None,
        _ => return None,
    };

    let agent = event_obj
        .get("agent")
        .and_then(Value::as_object)
        .or_else(|| {
            payload
                .and_then(|p| p.get("agent"))
                .and_then(Value::as_object)
        });

    let hook_name = payload
        .and_then(|p| p.get("hook_event_name"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_lowercase();

    let event_name = event_obj.get("name").and_then(Value::as_str).unwrap_or("");

    let category = agent
        .and_then(|a| a.get("category"))
        .and_then(Value::as_str)
        .or_else(|| {
            payload
                .and_then(|p| p.get("category"))
                .and_then(Value::as_str)
        })
        .unwrap_or("")
        .to_lowercase();

    let is_completion = (event_name.starts_with("agent.hook.")
        && (hook_name == "stop" || hook_name == "sessionend"))
        || category == "turn-complete";

    if !is_completion {
        return None;
    }

    let surface_id = event_obj
        .get("surface_id")
        .and_then(Value::as_str)
        .or_else(|| {
            payload
                .and_then(|p| p.get("surface_id"))
                .and_then(Value::as_str)
        })?
        .to_string();

    if surface_id.is_empty() {
        return None;
    }

    let event_id = event_obj
        .get("id")
        .and_then(Value::as_str)
        .map(ToString::to_string);

    let workspace_id = event_obj
        .get("workspace_id")
        .and_then(Value::as_str)
        .or_else(|| {
            payload
                .and_then(|p| p.get("workspace_id"))
                .and_then(Value::as_str)
        })
        .map(ToString::to_string);

    let agent_kind = agent
        .and_then(|a| a.get("kind"))
        .and_then(Value::as_str)
        .or_else(|| {
            payload
                .and_then(|p| p.get("_source"))
                .and_then(Value::as_str)
        })
        .map(ToString::to_string);

    Some(AgentSessionCompleted {
        event_id,
        workspace_id,
        surface_id,
        agent_kind,
        category: default_turn_complete(),
        extra: Map::new(),
    })
}

/// Matches cmux's compact `list-notifications` record format without forwarding notification text.
/// Format: `index:notification_id|workspace_id|surface_id|unread_state|title||Status|timestamp|...`
/// Returns true if status matches complete / completed / done.
pub fn notification_record_is_completion(record: &str, notification_id: &str) -> bool {
    let trimmed = record.trim_end_matches(['\r', '\n']);
    let fields: Vec<&str> = trimmed.split('|').collect();
    if fields.len() < 8 {
        return false;
    }

    // First field is usually "<idx>:<notification_id>" or just "<notification_id>"
    let id_part = fields[0].split(':').next_back().unwrap_or("").trim();
    if id_part != notification_id {
        return false;
    }

    let status = fields[6].trim().to_lowercase();
    matches!(status.as_str(), "complete" | "completed" | "done")
}
