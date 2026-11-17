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
    /// Specific reply from the matching structured notification. Absent when the
    /// only signal is a generic completion status.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    /// True when this event upgrades an already forwarded generic completion
    /// for the same turn. Clients replace that alert instead of posting another.
    #[serde(default, skip_serializing_if = "is_false")]
    pub content_update: bool,
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
            body: None,
            content_update: false,
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
            body: None,
            content_update: false,
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
        body: None,
        content_update: false,
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

fn is_false(value: &bool) -> bool {
    !*value
}

const REPLY_LIMIT: usize = 4000;
/// How close a structured notification must be to the completion event before
/// its reply can be attached. Older records on the same surface are history.
pub const REPLY_MATCH_WINDOW_SECS: i64 = 5;

/// One `list-notifications` record, with a specific reply separated from a
/// generic status word.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompletionNotice {
    pub id: String,
    pub surface_id: String,
    pub workspace_id: Option<String>,
    pub reply: Option<String>,
    pub is_completion: bool,
    pub created_unix: Option<i64>,
}

/// Status words cmux uses when a notification has no reply text.
fn is_generic_status(text: &str) -> bool {
    matches!(
        text.trim().to_ascii_lowercase().as_str(),
        "complete"
            | "completed"
            | "done"
            | "waiting"
            | "waiting for input"
            | "needs input"
            | "idle"
    )
}

fn specific_reply(body: &str) -> Option<String> {
    let trimmed = body.trim();
    if trimmed.is_empty() || is_generic_status(trimmed) {
        return None;
    }
    let mut reply = trimmed.to_string();
    if reply.chars().count() > REPLY_LIMIT {
        reply = reply.chars().take(REPLY_LIMIT).collect();
        reply.push('…');
    }
    Some(reply)
}

fn is_completion_notice(body: &str, subtitle: &str) -> bool {
    let body_status = body.trim().to_ascii_lowercase();
    if matches!(body_status.as_str(), "complete" | "completed" | "done") {
        return true;
    }
    subtitle
        .trim()
        .to_ascii_lowercase()
        .starts_with("completed")
}

/// Parses one object from `cmux list-notifications --json`.
pub fn completion_notice_from_json(value: &Value) -> Option<CompletionNotice> {
    let id = value.get("id")?.as_str()?.to_string();
    let surface_id = value.get("surface_id")?.as_str()?.to_string();
    if surface_id.is_empty() {
        return None;
    }
    let body = value.get("body").and_then(Value::as_str).unwrap_or("");
    let subtitle = value.get("subtitle").and_then(Value::as_str).unwrap_or("");
    Some(CompletionNotice {
        id,
        surface_id,
        workspace_id: value
            .get("workspace_id")
            .and_then(Value::as_str)
            .filter(|id| !id.is_empty())
            .map(ToString::to_string),
        reply: specific_reply(body),
        is_completion: is_completion_notice(body, subtitle),
        created_unix: value
            .get("created_at")
            .and_then(Value::as_str)
            .and_then(rfc3339_unix),
    })
}

/// Parses one compact `list-notifications` line.
///
/// Format: `index:id|workspace|surface|read|title|subtitle|body|timestamp|...`
pub fn completion_notice_from_record(record: &str) -> Option<CompletionNotice> {
    let fields: Vec<&str> = record.trim_end_matches(['\r', '\n']).split('|').collect();
    if fields.len() < 8 {
        return None;
    }
    let id = fields[0].split(':').next_back().unwrap_or("").trim();
    let surface_id = fields[2].trim();
    if id.is_empty() || surface_id.is_empty() {
        return None;
    }
    let workspace_id = fields[1].trim();
    Some(CompletionNotice {
        id: id.to_string(),
        surface_id: surface_id.to_string(),
        workspace_id: if workspace_id.is_empty() {
            None
        } else {
            Some(workspace_id.to_string())
        },
        reply: specific_reply(fields[6]),
        is_completion: is_completion_notice(fields[6], fields[5]),
        created_unix: rfc3339_unix(fields[7]),
    })
}

/// Picks the structured notification that belongs to this completion.
///
/// A notification id matches only that record. A Stop hook has no id, so the
/// reply must be a completion notice on the same surface within
/// [`REPLY_MATCH_WINDOW_SECS`]. Other surfaces and older history are ignored.
pub fn select_completion_notice<'a>(
    notices: &'a [CompletionNotice],
    notification_id: Option<&str>,
    surface_id: &str,
    occurred_at: Option<&str>,
) -> Option<&'a CompletionNotice> {
    if let Some(notification_id) = notification_id {
        return notices
            .iter()
            .find(|notice| notice.id == notification_id && notice.is_completion);
    }
    let occurred = occurred_at.and_then(rfc3339_unix)?;
    notices
        .iter()
        .filter(|notice| {
            notice.is_completion
                && notice.surface_id == surface_id
                && notice.reply.is_some()
                && notice
                    .created_unix
                    .is_some_and(|created| (created - occurred).abs() <= REPLY_MATCH_WINDOW_SECS)
        })
        .min_by_key(|notice| {
            notice
                .created_unix
                .map(|created| (created - occurred).abs())
                .unwrap_or(i64::MAX)
        })
}

fn rfc3339_unix(raw: &str) -> Option<i64> {
    let s = raw.trim().trim_end_matches('Z');
    let (date, time) = s.split_once('T')?;
    let mut date_parts = date.split('-');
    let year: i32 = date_parts.next()?.parse().ok()?;
    let month: u32 = date_parts.next()?.parse().ok()?;
    let day: u32 = date_parts.next()?.parse().ok()?;
    let time = time.split(['+', '-']).next()?;
    let mut time_parts = time.split(':');
    let hour: u32 = time_parts.next()?.parse().ok()?;
    let minute: u32 = time_parts.next()?.parse().ok()?;
    let second: u32 = time_parts.next()?.split('.').next()?.parse().ok()?;
    if !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || hour > 23
        || minute > 59
        || second > 60
    {
        return None;
    }
    Some(
        days_from_civil(year, month, day) * 86_400
            + i64::from(hour) * 3_600
            + i64::from(minute) * 60
            + i64::from(second),
    )
}

/// Howard Hinnant's civil-from-days, inverted. Days since 1970-01-01.
fn days_from_civil(year: i32, month: u32, day: u32) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = (y - era * 400) as u32;
    let doy = (153 * (month + if month > 2 { 9 } else { 3 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    i64::from(era) * 146_097 + i64::from(doe) - 719_468
}
