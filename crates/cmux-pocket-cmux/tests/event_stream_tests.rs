use cmux_pocket_cmux::CmuxEventStream;
use serde_json::json;
use tokio::sync::mpsc;

#[tokio::test]
async fn test_mock_event_stream_from_values() {
    let events = vec![
        json!({"type": "event", "name": "session.start", "id": "ev-1"}),
        json!({"type": "event", "name": "agent.complete", "id": "ev-2"}),
    ];

    let mut stream = CmuxEventStream::from_values(events);

    let ev1 = stream.next_event().await.unwrap();
    assert!(ev1.is_some());
    assert_eq!(ev1.unwrap()["id"], "ev-1");

    let ev2 = stream.next_event().await.unwrap();
    assert!(ev2.is_some());
    assert_eq!(ev2.unwrap()["id"], "ev-2");

    // Reached end of stream (EOF)
    let eof = stream.next_event().await.unwrap();
    assert!(eof.is_none());
}

#[tokio::test]
async fn test_mock_event_stream_channel_delivery() {
    let (tx, rx) = mpsc::unbounded_channel();
    let mut stream = CmuxEventStream::mock(rx);

    tx.send(json!({"type": "notification", "id": "n-1"}))
        .unwrap();
    let ev = stream.next_event().await.unwrap();
    assert_eq!(ev.unwrap()["id"], "n-1");

    drop(tx);
    let eof = stream.next_event().await.unwrap();
    assert!(eof.is_none());
}
