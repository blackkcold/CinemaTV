import { useEffect, useState } from 'react';
import { createPlatform } from './platform';
import type { Snapshot } from './types';
export function App() {
  const [state, setState] = useState<Snapshot | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let disposed = false; let cleanup = () => {};
    void createPlatform().then(async client => {
      if (disposed) { client.close(); return; }
      const unsub = client.subscribe((name, payload) => { if (name === 'state') setState(payload as Snapshot); });
      cleanup = () => { unsub(); client.close(); };
      const reply = await client.call('app.ready', { protocol: 1 });
      if (!disposed && reply.state) setState(reply.state);
    }).catch(e => { if (!disposed) setError(String(e.message)); });
    return () => { disposed = true; cleanup(); };
  }, []);
  return <main className="boot"><span className="wordmark">CINEMA<span>TV</span></span><h1>{state ? '影院，回到客厅。' : '正在打开 CinemaTV'}</h1><p>{error || state?.notice || '连接本机影视资料'}</p></main>;
}
