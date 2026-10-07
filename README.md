# Zombie AI Evolution — Minecraft Java 26.3

Mod Fabric para Minecraft Java 26.3 que transforma o comportamento do Zombie em uma IA de sobrevivência.

## Mecânicas

- Procura troncos próximos e quebra árvores.
- Coleta drops de madeira.
- Converte madeira em tábuas, gravetos e Crafting Table.
- Coloca a Crafting Table no mundo e fabrica espada/picareta de madeira.
- Coleta equipamentos e ferramentas encontrados no chão e equipa os melhores.
- Procura vacas, porcos e ovelhas, persegue e ataca.
- Recolhe carne e usa alimento quando está ferido.
- Persegue jogadores e tenta quebrar blocos comuns que bloqueiam o caminho.
- Não substitui o sistema vanilla inteiro: o comportamento é adicionado por um controlador server-side.

## Build

O GitHub Actions deste repositório compila automaticamente a cada push e também pode ser executado manualmente.

O `.jar` aparece em **Actions → Build Zombie AI → Artifacts**.

## Compatibilidade

- Minecraft Java 26.3
- Fabric Loader 0.19.5+
- Fabric API 0.161.0+26.3
- Java 25+
- SKLauncher: use um perfil Fabric 26.3 e coloque o `.jar` compilado na pasta `mods`.
