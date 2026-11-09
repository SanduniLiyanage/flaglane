/** One Server-Sent Event: its type, its data with lines joined by "\n", and its id if it had one. */
export interface StreamEvent {
  readonly event: string;
  readonly data: string;
  readonly id: string | undefined;
}

/**
 * The most text the parser holds while waiting for the end of a line or an event. A server that
 * sends more than this without ending one is not speaking the protocol, and the stream is dropped.
 */
export const MAX_PENDING_CHARACTERS = 1_048_576;

/**
 * Turns the text of an event stream, in whatever pieces it arrives, into events, as the HTML
 * standard's event-stream interpretation does: lines end with CRLF, LF or CR; a line starting with
 * a colon is a comment; a blank line ends an event; an event with no data is not an event.
 * `retry` is ignored, since the SDK schedules its own reconnections.
 */
export class EventStreamParser {
  private buffer = "";
  /** The last text ended with CR, so an LF starting the next is the second half of a CRLF. */
  private skipLineFeed = false;
  private type = "";
  private data: string[] = [];
  private id: string | undefined;

  /** The events the text completes. Throws if the stream holds too much without ending a line. */
  push(text: string): StreamEvent[] {
    if (text === "") {
      return [];
    }
    this.buffer += this.skipLineFeed && text.startsWith("\n") ? text.slice(1) : text;
    this.skipLineFeed = false;
    const events: StreamEvent[] = [];
    let start = 0;
    for (;;) {
      const end = lineEnd(this.buffer, start);
      if (end === -1) {
        break;
      }
      const line = this.buffer.slice(start, end);
      if (this.buffer[end] === "\r" && end === this.buffer.length - 1) {
        // The line ends here; an LF arriving next would be the rest of a CRLF.
        this.skipLineFeed = true;
        start = end + 1;
      } else {
        start = end + (this.buffer[end] === "\r" && this.buffer[end + 1] === "\n" ? 2 : 1);
      }
      const event = this.line(line);
      if (event !== undefined) {
        events.push(event);
      }
    }
    this.buffer = this.buffer.slice(start);
    const pending = this.buffer.length + this.data.reduce((total, line) => total + line.length, 0);
    if (pending > MAX_PENDING_CHARACTERS) {
      throw new Error("The event stream sent too much without ending an event");
    }
    return events;
  }

  private line(line: string): StreamEvent | undefined {
    if (line === "") {
      return this.dispatch();
    }
    if (line.startsWith(":")) {
      return undefined;
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    if (field === "event") {
      this.type = value;
    } else if (field === "data") {
      this.data.push(value);
    } else if (field === "id" && !value.includes("\u0000")) {
      this.id = value;
    }
    return undefined;
  }

  private dispatch(): StreamEvent | undefined {
    const event =
      this.data.length === 0
        ? undefined
        : { event: this.type === "" ? "message" : this.type, data: this.data.join("\n"), id: this.id };
    this.type = "";
    this.data = [];
    return event;
  }
}

function lineEnd(text: string, from: number): number {
  for (let i = from; i < text.length; i++) {
    const character = text[i];
    if (character === "\n" || character === "\r") {
      return i;
    }
  }
  return -1;
}
