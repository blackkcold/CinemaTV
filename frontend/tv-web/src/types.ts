export type Route = 'HOME' | 'EXPLORE' | 'MOVIES' | 'SERIES' | 'SEARCH' | 'DETAIL' | 'LIVE' | 'LIBRARY' | 'SETTINGS' | 'PLAYER';
export interface Card {
  key: string; title: string; poster: string; backdrop: string; year: string; category: string;
  description: string; rating: string; ratingSource: string; isTv: boolean; favorite: boolean;
  status: string; positionMs: number; durationMs: number; lastEpisode: number; sourceName: string; attribution: string;
}
export interface Episode { id: string; index: number; label: string; progress: number | null; }
export interface Shelf { id: string; title: string; items: Card[]; }
export interface Channel { id: string; name: string; group: string; logo: string; }
export interface Bookmark { route: string; focus: string; scrolls: Record<string, [number, number]>; }
export interface Snapshot {
  protocol: 1; revision: number; route: Route; routeKey: string; section: Route;
  busy: boolean; message: string; quality: string; reducedMotion: boolean; lowMemory: boolean;
  connected: boolean; authorized: boolean; version: string; channel: string; scene: Card | null;
  shelves: Shelf[]; items: Card[]; total: number; pageIndex: number; pageSize: number; canLoadMore: boolean;
  notice: string; attribution: string; selected: Card | null; episodes: Episode[];
  episodePage: number; episodeTotal: number; alternatives: Card[]; query: string; history: string[];
  filters: { type: string; year: string; minRating: number; topOnly: boolean }; years: string[];
  channels: Channel[]; activeChannel: string; liveSources: { id: string; name: string }[]; activeLiveSource: string;
  programs: { title: string; start: number; stop: number }[];
  libraryFilter: string; bookmark: Bookmark | null;
  qualityOptions: { id: string; title: string }[];
}
export interface Reply { accepted?: boolean; state?: Snapshot; [key: string]: unknown; }
export interface Platform {
  native: boolean;
  call(method: string, params?: Record<string, unknown>): Promise<Reply>;
  subscribe(listener: (event: string, payload: unknown) => void): () => void;
  close(): void;
}
