const labels: Record<string, string> = {
  CREATED: 'Draft', REGISTRATION_OPEN: 'Registration Open', REGISTRATION_CLOSED: 'Registration Closed',
  RUNNING: 'In Progress', STARTING: 'Starting…', FINISHED: 'Finished', CANCELLED: 'Cancelled', ERROR: 'Error',
  EXECUTABLE: 'Ready to compete', NOT_EXECUTABLE: 'Not ready to compete', SOURCE_ACCEPTED: 'Source accepted',
  DRAW: 'Draw', WIN: 'Win', FORFEIT: 'Forfeit', ROUND_ROBIN: 'Round Robin', SINGLE_ELIMINATION: 'Single Elimination',
  BYE: 'Bye', PENDING: 'Pending', SCHEDULED: 'Scheduled', INTERRUPTED: 'Interrupted',
};
export function productLabel(value: string) { return labels[value] ?? value.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase()); }
