import { events } from 'mantis:events';
import { clock } from 'mantis:clock';
import { recipes } from 'minecraft:recipes';

clock.every(1, () => {});
events.once('server.started', () => clock.cooldown('mantis:smoke', 1000000));

events.on('recipes', () => {
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
