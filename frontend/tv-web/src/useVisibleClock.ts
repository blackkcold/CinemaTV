import { useEffect, useState } from 'react';

/** Timers pause with the host; returning to the guide immediately rechecks wall time. */
export function useVisibleClock(intervalMs = 30_000): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | undefined;
    const synchronize = () => {
      clearInterval(timer);
      timer = undefined;
      if (document.hidden || document.documentElement.dataset.paused === 'true') return;
      setNow(Date.now());
      timer = setInterval(() => setNow(Date.now()), intervalMs);
    };
    document.addEventListener('visibilitychange', synchronize);
    window.addEventListener('focus', synchronize);
    window.addEventListener('cinema:visibility', synchronize);
    synchronize();
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', synchronize);
      window.removeEventListener('focus', synchronize);
      window.removeEventListener('cinema:visibility', synchronize);
    };
  }, [intervalMs]);
  return now;
}
