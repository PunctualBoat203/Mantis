import { events } from 'mantis:events';
import { clock } from 'mantis:clock';
import { recipes } from 'minecraft:recipes';
import { lifecycle } from 'mantis:lifecycle';
import { test } from 'mantis_test:async';

test.hold();
test.load().then(data => {
  if (!test.onServerThread() || data.id !== 'mantis:async' || data.component.text !== 'async'
      || !Array.isArray(data.values) || data.values.reduce((a, b) => a + b, 0) !== 42) {
    throw new Error('Async host conversion or server scheduling failed');
  }
  clock.cooldown('mantis:async', 1000000);
});
lifecycle.on('start', () => {
  if (lifecycle.state() !== 'running' || !test.onServerThread()) throw new Error('Wrong lifecycle thread or state');
  clock.cooldown('mantis:lifecycle', 1000000);
});

clock.every(1, () => {});
events.once('server.started', () => clock.cooldown('mantis:smoke', 1000000));

events.on('recipes', () => {
  recipes.set({ type: 'mantis:test_infusion' }, '/fusion/energy', 32000);
  recipes.set({ id: 'mantis:infusion' }, '/outputs/0/count', 2);
  recipes.patch({ type: 'mantis:test_infusion' }, json => {
    json.ritual.duration = 400;
    return json;
  });
  recipes.custom('mantis:smoke', {
    type: 'minecraft:crafting_shapeless',
    ingredients: [{ item: 'minecraft:stone' }],
    result: { item: 'minecraft:stick', count: 2 }
  });
  recipes.patch({ id: 'mantis:smoke' }, json => {
    json.result.count = 4;
    return json;
  });
});
