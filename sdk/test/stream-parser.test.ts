import { describe, expect, it } from "vitest";
import { EventStreamParser, MAX_PENDING_CHARACTERS } from "../src/stream.js";

/** The event-stream format as the HTML standard reads it, which is what Flaglane writes. */
describe("the event stream parser", () => {
  it("reads an event with its type, id and data", () => {
    const events = new EventStreamParser().push(
      'event: ruleset-changed\nid: 413\ndata: {"environment":"production","version":413}\n\n',
    );

    expect(events).toEqual([
      { event: "ruleset-changed", id: "413", data: '{"environment":"production","version":413}' },
    ]);
  });

  it("puts an event back together from pieces split anywhere, CRLF included", () => {
    const parser = new EventStreamParser();
    const text = "event: ruleset-changed\r\nid: 7\r\ndata: one\r\n\r\n";
    const events = [...text].flatMap((character) => parser.push(character));

    expect(events).toEqual([{ event: "ruleset-changed", id: "7", data: "one" }]);
  });

  it("accepts lines ended by CR alone", () => {
    expect(new EventStreamParser().push("data: a\rdata: b\r\r")).toEqual([
      { event: "message", id: undefined, data: "a\nb" },
    ]);
  });

  it("passes over comments, which is what a heartbeat is", () => {
    expect(new EventStreamParser().push(":heartbeat\n\n: another\n\ndata: x\n\n")).toEqual([
      { event: "message", id: undefined, data: "x" },
    ]);
  });

  it("dispatches nothing for an event without data", () => {
    expect(new EventStreamParser().push("event: ruleset-changed\nid: 3\n\n")).toEqual([]);
  });

  it("refuses to hold an endless line", () => {
    const parser = new EventStreamParser();

    expect(() => parser.push("data: " + "x".repeat(MAX_PENDING_CHARACTERS + 1))).toThrow();
  });
});
