export type Route = 'HOME' | 'EXPLORE' | 'MOVIES' | 'SERIES' | 'SEARCH' | 'DETAIL' | 'LIVE' | 'LIBRARY' | 'SETTINGS' | 'PLAYER';
export interface Card {
  key: string; title: string; poster: string; backdrop: string; year: string; category: string;
  description: string; rating: string; ratingSource: string; isTv: boolean; favorite: boolean;
  status: string; positionMs: number; durationMs: number; lastEpisode: number; sourceName: string; attribution: string;
}
export interface Episode { id: string; index: number; label: string; progress: number | null; completed: boolean; }
export interface Shelf { id: string; title: string; items: Card[]; }
export interface Channel { id: string; name: string; group: string; logo: string; }
export interface Bookmark { route: string; focus: string; scrolls: Record<string, [number, number]>; }
export interface CatalogFilters { category: string; year: string; rated: boolean; }
export type PagingStatus = 'unavailable' | 'ready' | 'loading' | 'retry' | 'restart' | 'exhausted';
export interface Snapshot {
  protocol: 1; revision: number; route: Route; routeKey: string; section: Route;
  busy: boolean; message: string; quality: string; effectiveQuality: string; effectsLimited: boolean; reducedMotion: boolean; lowMemory: boolean;
  connected: boolean; authorized: boolean; version: string; channel: string; scene: Card | null;
  shelves: Shelf[]; items: Card[]; total: number; pageIndex: number; pageSize: number; canLoadMore: boolean; pagingStatus: PagingStatus;
  notice: string; attribution: string; recommendationReason: string; selected: Card | null; episodes: Episode[];
  resumeEpisode: Episode | null;
  episodePage: number; episodeTotal: number; alternatives: Card[]; query: string; history: string[];
  filters: { type: string; year: string; minRating: number; ratingSource: string; topOnly: boolean }; years: string[];
  catalogFilters: CatalogFilters; categories: string[]; catalogYears: string[];
  liveGroups: { id: string; name: string }[]; liveGroup: string; channels: Channel[]; activeChannel: string; liveSources: { id: string; name: string }[]; activeLiveSource: string;
  programs: { id: string; title: string; start: number; stop: number }[];
  libraryFilter: string; hasHistory: boolean; quarantinedProgressCount: number; bookmark: Bookmark | null;
  qualityOptions: { id: string; title: string }[];
}
export interface Reply { accepted?: boolean; state?: Snapshot; [key: string]: unknown; }
export interface Platform {
  native: boolean;
  call(method: string, params?: Record<string, unknown>): Promise<Reply>;
  subscribe(listener: (event: string, payload: unknown) => void): () => void;
  close(): void;
}
