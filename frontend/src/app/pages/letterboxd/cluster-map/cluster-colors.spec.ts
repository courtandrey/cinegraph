import { MapCluster } from '../../../models/movie.model';
import { GENRE_COLORS, NEUTRAL_COLOR, clusterColors, distance, hexToLab, labToHex } from './cluster-colors';

const HORROR = 27;
const THRILLER = 53;
const ANIMATION = 16;
const FAMILY = 10751;

function cluster(id: number, genres: Record<number, number>, kind: 'CLUSTER' | 'GROUP' = 'CLUSTER'): MapCluster {
  return {
    id, kind, componentId: 1, hubId: id, size: 20, x: 0, y: 0, radius: 100, contributors: [],
    genres: Object.entries(genres).map(([g, films]) => ({ genreId: +g, name: g, films }))
  };
}

describe('clusterColors', () => {
  it('round-trips sRGB through OKLab', () => {
    for (const hex of Object.values(GENRE_COLORS)) {
      expect(distance(hexToLab(labToHex(hexToLab(hex))), hexToLab(hex))).toBeLessThan(0.01);
    }
  });

  it('colours a cluster by the genres it has more of than the set, not by the set-wide majority', () => {
    const colors = clusterColors([
      cluster(1, { [HORROR]: 30, [THRILLER]: 10 }),
      cluster(2, { [HORROR]: 12, [ANIMATION]: 10, [FAMILY]: 9 })
    ]);
    const family = hexToLab(colors.get(2)!);
    expect(distance(family, hexToLab(GENRE_COLORS[ANIMATION])))
      .toBeLessThan(distance(family, hexToLab(GENRE_COLORS[HORROR])));
  });

  it('keeps every pair of clusters apart even when their genre mixes are identical', () => {
    const clusters = Array.from({ length: 14 }, (_, i) => cluster(i + 1, { [HORROR]: 20, [THRILLER]: 8 }));
    const labs = [...clusterColors(clusters).values()].map(hexToLab);
    for (let i = 0; i < labs.length; i++) {
      for (let j = i + 1; j < labs.length; j++) expect(distance(labs[i], labs[j])).toBeGreaterThan(0.05);
    }
  });

  it('is deterministic and leaves groups neutral', () => {
    const clusters = [cluster(1, { [HORROR]: 5 }), cluster(2, { [ANIMATION]: 5 }), cluster(3, { [HORROR]: 2 }, 'GROUP')];
    expect(clusterColors(clusters)).toEqual(clusterColors([...clusters].reverse()));
    expect(clusterColors(clusters).get(3)).toBe(NEUTRAL_COLOR);
  });
});
