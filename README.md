# RentABot

A production-ready Minecraft plugin that allows players to rent bot accounts for AFK farming using real Minecraft client connections via MCProtocolLib.

## Features

- **Real Bot Connections** - Uses MCProtocolLib to connect bots as real Minecraft clients
- **Full GUI System** - BankPlus-style inventory menus for easy management
- **ShopGUIPlus Integration** - Seamless shop integration without double-charging
- **Economy Integration** - Vault economy support for hourly rental fees
- **AuthMe Support** - Auto-login/register for AuthMe servers
- **Anti-AFK System** - Configurable movements (look, sneak, jump, move)
- **Database Storage** - SQLite/MySQL support with HikariCP connection pooling
- **Spawn Point Saving** - Bots remember death locations and respawn there
- **TPA Acceptance** - Configurable patterns for accepting teleport requests
- **PlaceholderAPI** - Built-in placeholders for bot stats

## Requirements

- Paper/Spigot 1.21+
- Java 17+
- Vault (for economy)
- Optional: AuthMe, PlaceholderAPI, ShopGUIPlus

## Installation

1. Download the latest `RentABot-x.x.x.jar` from releases
2. Place in your `plugins` folder
3. Restart server
4. Configure `plugins/RentABot/config.yml`
5. (Optional) Add to ShopGUIPlus using `shopguiplus-example.yml`

## Commands

### Player Commands
- `/rentabot` - Open main GUI
- `/rentabot create <hours> [name]` - Rent a bot
- `/rentabot extend <hours>` - Extend rental
- `/rentabot stop <name>` - Stop a bot
- `/rentabot list` - List your bots
- `/rentabot status <name>` - Check bot status
- `/rentabot tp <name>` - Teleport to bot
- `/rentabot tpahere <name>` - Send TPA to bot
- `/rentabot rename <name> <newname>` - Rename bot
- `/rentabot gui` - Open GUI
- `/rentabot shop` - Open shop GUI

### Admin Commands
- `/rabadmin give <player> <hours> [name]` - Create free bot
- `/rabadmin reload` - Reload config
- `/rabadmin list [player]` - List all/player bots
- `/rabadmin stop <player> <name>` - Stop any bot
- `/rabadmin stopall <player>` - Stop all player bots

## Configuration

Key config options:
```yaml
economy:
  cost-per-hour: 100.0
  
bots:
  behavior:
    anti-afk:
      enabled: true
      interval: 15
      type: "look, sneak, jump"
```

See `config.yml` for full configuration options.

## Permissions

- `rentabot.use` - Use basic bot commands
- `rentabot.create` - Create bots
- `rentabot.admin` - Admin commands
- `rentabot.limit.<number>` - Set max bots per player

## ShopGUIPlus Integration

Example shop configuration:
```yaml
bots:
  '1':
    type: command
    commands:
      - "console: rabadmin give %player% 24"
```

See `shopguiplus-example.yml` for full setup.

## Building

```bash
mvn clean package
```

Output: `target/RentABot-x.x.x.jar`

## Support

For issues, please create a GitHub issue with:
- Server version
- Plugin version
- Full error logs
- Steps to reproduce

## License

All rights reserved.

## Credits

- MCProtocolLib by GeyserMC
- Vault API
- Paper API
