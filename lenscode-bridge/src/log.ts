type Level = "debug" | "info" | "warn" | "error";

export function log(level: Level, msg: string, fields: Record<string, unknown> = {}) {
  const entry = { ts: new Date().toISOString(), level, msg, ...fields };
  const line = JSON.stringify(entry);
  if (level === "error" || level === "warn") process.stderr.write(line + "\n");
  else process.stdout.write(line + "\n");
}
