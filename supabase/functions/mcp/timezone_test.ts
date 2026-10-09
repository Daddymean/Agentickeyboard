import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import { addDays, dayBounds, isValidTimeZone, localDate, parseDate, startOfDay } from "./timezone.ts";

function iso(date: string, tz: string) {
  const { start, end } = dayBounds(date, tz);
  return [start.toISOString(), end.toISOString()];
}

/** Brute force: first minute whose local date is `date`. */
function scanStart(date: string, tz: string): number {
  const wall = parseDate(date)!;
  for (let t = wall - 15 * 3_600_000; ; t += 60_000) {
    if (localDate(t, tz) === date) return t;
  }
}

Deno.test("parseDate is strict", () => {
  assertEquals(parseDate("2026-02-28"), Date.UTC(2026, 1, 28));
  assertEquals(parseDate("2024-02-29"), Date.UTC(2024, 1, 29));
  assertEquals(parseDate("2026-02-29"), null);
  assertEquals(parseDate("2026-13-01"), null);
  assertEquals(parseDate("2026-1-01"), null);
  assertEquals(parseDate("2026-01-01T00:00"), null);
  assertEquals(addDays("2026-12-31", 1), "2027-01-01");
  assertEquals(addDays("2024-02-28", 1), "2024-02-29");
});

Deno.test("UTC day bounds", () => {
  assertEquals(iso("2026-03-08", "UTC"), ["2026-03-08T00:00:00.000Z", "2026-03-09T00:00:00.000Z"]);
});

Deno.test("New York: spring-forward day is 23h, fall-back day is 25h", () => {
  assertEquals(iso("2026-03-08", "America/New_York"), [
    "2026-03-08T05:00:00.000Z",
    "2026-03-09T04:00:00.000Z",
  ]);
  assertEquals(iso("2026-11-01", "America/New_York"), [
    "2026-11-01T04:00:00.000Z",
    "2026-11-02T05:00:00.000Z",
  ]);
  assertEquals(iso("2026-07-04", "America/New_York"), [
    "2026-07-04T04:00:00.000Z",
    "2026-07-05T04:00:00.000Z",
  ]);
});

Deno.test("London DST and half-hour/east-of-UTC zones", () => {
  assertEquals(iso("2026-03-29", "Europe/London"), [
    "2026-03-29T00:00:00.000Z",
    "2026-03-29T23:00:00.000Z",
  ]);
  assertEquals(iso("2026-10-25", "Europe/London"), [
    "2026-10-24T23:00:00.000Z",
    "2026-10-26T00:00:00.000Z",
  ]);
  assertEquals(iso("2026-01-01", "Asia/Kolkata"), [
    "2025-12-31T18:30:00.000Z",
    "2026-01-01T18:30:00.000Z",
  ]);
  assertEquals(iso("2026-04-05", "Australia/Sydney"), [
    "2026-04-04T13:00:00.000Z",
    "2026-04-05T14:00:00.000Z",
  ]);
});

Deno.test("matches brute force, including zones whose DST jumps at midnight", () => {
  const cases: Array<[string, string]> = [
    ["America/Santiago", "2024-09-08"], // 00:00 skipped → day starts 01:00
    ["America/Santiago", "2024-04-06"],
    ["America/Santiago", "2024-04-07"],
    ["America/Havana", "2026-03-08"],
    ["Asia/Beirut", "2026-03-29"],
    ["America/New_York", "2026-03-08"],
    ["America/New_York", "2026-11-01"],
    ["Pacific/Chatham", "2026-04-05"],
    ["Pacific/Kiritimati", "2026-06-01"],
  ];
  // Chile: 2024-09-08 00:00 -04 does not exist; the day starts at 01:00 -03.
  assertEquals(iso("2024-09-08", "America/Santiago"), [
    "2024-09-08T04:00:00.000Z",
    "2024-09-09T03:00:00.000Z",
  ]);
  // 2024-04-06 24:00 -03 → 23:00 -04: a 25h day ending at 00:00 -04.
  assertEquals(iso("2024-04-06", "America/Santiago"), [
    "2024-04-06T03:00:00.000Z",
    "2024-04-07T04:00:00.000Z",
  ]);
  for (const [tz, date] of cases) {
    assertEquals(startOfDay(date, tz), scanStart(date, tz), `${tz} ${date}`);
    const next = addDays(date, 1);
    assertEquals(startOfDay(next, tz), scanStart(next, tz), `${tz} ${next}`);
  }
});

Deno.test("time zone validation", () => {
  assertEquals(isValidTimeZone("Europe/Berlin"), true);
  assertEquals(isValidTimeZone("Not/AZone"), false);
  assertThrows(() => dayBounds("2026-02-30", "UTC"));
});
