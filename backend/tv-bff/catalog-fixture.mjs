// All names, scores and rankings are synthetic test data, not a shipped catalog.
export function fixture() {
  return {
    schemaVersion: 1, provider: 'Synthetic provider', attribution: 'Synthetic fixture only',
    license: { name: 'Synthetic testing declaration', url: 'https://license.example.test/terms', expiresAt: '2099-01-01T00:00:00Z' },
    items: [{ providerId: 'one', title: 'Example Film', type: 'movie', year: '2025',
      aliases: ['Alternate Title'], actors: ['Fictional Actor'], rating: 8.5, ratingSource: 'Synthetic ratings',
      ratingScale: 10, topRank: 5, topSource: 'Synthetic chart' },
    { providerId: 'two', title: 'Example Series', type: 'tv' },
    { providerId: 'three', title: 'Unrated Film', type: 'movie' }]
  };
}
