import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './tv.css';
class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { window.CinemaNative?.postMessage(JSON.stringify({ v: 1, id: 'ui_fatal', method: 'app.fatal', params: {} })); }
  render() { return this.state.failed ? <main className="boot"><h1>界面暂时不可用</h1><p>按返回键退出，或重新打开 CinemaTV。</p></main> : this.props.children; }
}
createRoot(document.getElementById('root')!).render(<ErrorBoundary><App /></ErrorBoundary>);
