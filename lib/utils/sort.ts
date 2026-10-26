/**
 * Sort utility functions for search results
 */

import type { SortOption } from '@/lib/store/settings-store';
import type { Video } from '@/lib/types';

/**
 * Some upstream sources expose extra fields that are not part of the shared `Video`
 * interface (e.g. `vod_score`). They are read defensively, so a partial shape is used.
 */
type SortableVideo = Video & {
  vod_score?: number | string;
};

const relevanceScoreOf = (video: Video): number => video.relevanceScore || 0;
const ratingOf = (video: Video): number => {
  const score = (video as SortableVideo).vod_score;
  const parsed = typeof score === 'string' ? parseFloat(score) : score;
  return typeof parsed === 'number' && !Number.isNaN(parsed) ? parsed : 0;
};

export function sortVideos(videos: Video[], sortBy: SortOption): Video[] {
  const sorted = [...videos];

  switch (sortBy) {
    case 'relevance':
      // Sort by relevance score (highest first)
      return sorted.sort((a, b) => {
        const scoreA = relevanceScoreOf(a);
        const scoreB = relevanceScoreOf(b);
        return scoreB - scoreA;
      });

    case 'latency-asc':
      // Sort by latency (lowest first)
      return sorted.sort((a, b) => {
        const latencyA = a.latency || 99999;
        const latencyB = b.latency || 99999;
        return latencyA - latencyB;
      });

    case 'date-desc':
      // Sort by year (newest first)
      return sorted.sort((a, b) => {
        const yearA = parseInt(a.vod_year || '0');
        const yearB = parseInt(b.vod_year || '0');
        return yearB - yearA;
      });

    case 'date-asc':
      // Sort by year (oldest first)
      return sorted.sort((a, b) => {
        const yearA = parseInt(a.vod_year || '0');
        const yearB = parseInt(b.vod_year || '0');
        return yearA - yearB;
      });

    case 'rating-desc':
      // Sort by rating if available (placeholder for future implementation)
      return sorted.sort((a, b) => {
        const ratingA = ratingOf(a);
        const ratingB = ratingOf(b);
        return ratingB - ratingA;
      });

    case 'name-asc':
      // Sort by name A-Z
      return sorted.sort((a, b) => {
        return a.vod_name.localeCompare(b.vod_name, 'zh-CN');
      });

    case 'name-desc':
      // Sort by name Z-A
      return sorted.sort((a, b) => {
        return b.vod_name.localeCompare(a.vod_name, 'zh-CN');
      });

    case 'default':
    default:
      // Default: by relevance then latency
      return sorted.sort((a, b) => {
        const scoreA = relevanceScoreOf(a);
        const scoreB = relevanceScoreOf(b);

        if (scoreA !== scoreB) {
          return scoreB - scoreA;
        }

        const latencyA = a.latency || 99999;
        const latencyB = b.latency || 99999;
        return latencyA - latencyB;
      });
  }
}
