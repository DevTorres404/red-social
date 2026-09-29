export const MAP_WIDTH = 900;
export const MAP_HEIGHT = 680;

const MAX_DIRECT = 10;
const MAX_SECOND = 14;

function includeSelected(items, selected, limit) {
  const visible = items.slice(0, limit);
  if (!selected || visible.some((item) => item.id === selected.id)) return visible;
  return [...visible.slice(0, limit - 1), selected];
}

function placeOnRing(people, radius, startAngle) {
  return people.map((person, index) => {
    const angle = startAngle + (index * Math.PI * 2) / people.length;
    return {
      ...person,
      x: MAP_WIDTH / 2 + radius * Math.cos(angle),
      y: MAP_HEIGHT / 2 + radius * Math.sin(angle),
    };
  });
}

function interleaveByConnection(people, direct) {
  const groups = direct.map((person) => people.filter((item) => item.viaId === person.id));
  const result = [];
  while (groups.some((group) => group.length)) {
    for (const group of groups) {
      if (group.length) result.push(group.shift());
    }
  }
  return result;
}

// Keep the drawing legible without hiding anyone from the searchable directory.
export function buildNetworkMap(self, reachable = [], selectedId = self.id) {
  const direct = reachable.filter((person) => person.distance === 1);
  const second = reachable.filter((person) => person.distance === 2);
  const selected = reachable.find((person) => person.id === selectedId);
  const requiredDirect = selected?.distance === 2
    ? direct.find((person) => person.id === selected.viaId)
    : selected?.distance === 1 ? selected : null;
  const visibleDirect = includeSelected(direct, requiredDirect, MAX_DIRECT);
  const directIds = new Set(visibleDirect.map((person) => person.id));
  const eligibleSecond = second.filter((person) => directIds.has(person.viaId));
  const visibleSecond = includeSelected(
    interleaveByConnection(eligibleSecond, visibleDirect),
    selected?.distance === 2 ? selected : null,
    MAX_SECOND,
  );

  const nodes = [
    { ...self, distance: 0, x: MAP_WIDTH / 2, y: MAP_HEIGHT / 2 },
    ...placeOnRing(visibleDirect, 176, -Math.PI / 2),
    ...placeOnRing(visibleSecond, 284, -Math.PI / 2),
  ];
  const edges = [
    ...visibleDirect.map((person) => ({ from: self.id, to: person.id })),
    ...visibleSecond.map((person) => ({ from: person.viaId, to: person.id })),
  ];
  return { nodes, edges, hiddenCount: reachable.length - nodes.length + 1 };
}
