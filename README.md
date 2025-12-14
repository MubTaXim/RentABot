# RentABot

[![GitHub release](https://img.shields.io/github/v/release/MubTaXim/RentABot)](https://github.com/MubTaXim/RentABot/releases)
[![Java](https://img.shields.io/badge/Java-17+-orange)](https://www.java.com)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21+-green)](https://papermc.io)

A production-ready Minecraft plugin that allows players to rent bot accounts for AFK farming using real Minecraft client connections via MCProtocolLib.

## Features

- **Real Bot Connections** - Uses MCProtocolLib to connect bots as real Minecraft clients
- **Full GUI System** - BankPlus-style inventory menus for easy management
- **Bot Lifecycle Management** - Stop, resume, and manage bot states (Active/Paused/Expired)
- **ShopGUIPlus Integration** - Seamless shop integration without double-charging
- **Economy Integration** - Vault economy support for hourly rental fees
- **AuthMe Support** - Auto-login/register for AuthMe servers
- **Anti-AFK System** - Configurable movements with randomized intervals
- **Database Storage** - SQLite/MySQL support with HikariCP connection pooling
- **Spawn Point Saving** - Bots remember death locations and respawn there
- **TPA Acceptance** - Configurable patterns for accepting teleport requests
- **PlaceholderAPI** - Built-in placeholders for bot stats
- **Auto-Update System** - Check and download updates directly from GitHub
- **Foolproof Reload** - Comprehensive reload system with verification

## Requirements

- Paper/Spigot 1.21+
- Java 17+
- Vault (for economy)
- Optional: AuthMe, PlaceholderAPI, Essentials, ShopGUIPlus

## Installation

1. Download the latest `RentABot-x.x.x.jar` from [releases](https://github.com/MubTaXim/RentABot/releases)
2. Place in your `plugins` folder
3. Restart server
4. Configure `plugins/RentABot/config.yml`
5. (Optional) Add to ShopGUIPlus using `shopguiplus-example.yml`

## Commands

### Player Commands
| Command | Description |
|---------|-------------|
| `/rentabot` | Open main GUI |
| `/rentabot create <hours> [name]` | Rent a new bot |
| `/rentabot stop <name>` | Pause a bot (saves remaining time) |
| `/rentabot resume <name> [hours]` | Resume a paused bot |
| `/rentabot delete <name>` | Permanently delete a bot |
| `/rentabot list` | List all your bots |
| `/rentabot tp <name>` | Teleport bot to you |
| `/rentabot extend <name> <hours>` | Extend rental time |
| `/rentabot rename <name> <newname>` | Rename a bot |
| `/rentabot shop` | Open rental shop GUI |
| `/rentabot help` | Show help message |

### Admin Commands
| Command | Description |
|---------|-------------|
| `/rabadmin list` | List all server bots |
| `/rabadmin stop <name>` | Force stop any bot |
| `/rabadmin stopall` | Stop all bots on server |
| `/rabadmin info <name>` | Get detailed bot info |
| `/rabadmin give <player> <hours> [name]` | Give a free bot |
| `/rabadmin reload [type]` | Reload configuration |
| `/rabadmin update [action]` | Update management |
| `/rabadmin debug` | Toggle debug mode |

### Reload Subcommands
```
/rabadmin reload              - Full reload (all components)
/rabadmin reload config       - Only config.yml
/rabadmin reload messages     - Only messages.yml  
/rabadmin reload tasks        - Reschedule background tasks
/rabadmin reload hooks        - Re-validate plugin hooks
```

### Update Subcommands
```
/rabadmin update check        - Check for new versions
/rabadmin update download     - Download latest version
/rabadmin update status       - Show update status
```

## Configuration

Key config options:
```yaml
economy:
  enabled: true
  price-per-hour: 5000
  min-hours: 1
  max-hours: 168  # 1 week

limits:
  max-active-bots: 3
  max-reserved-bots: 5
  max-total-bots: 50
  
bots:
  behavior:
    anti-afk:
      enabled: true
      interval: 45
      interval-randomness: 0.4  # ±40% variation

updates:
  check-for-updates: true
  notify-admins: true
```

See `config.yml` for full configuration options.

## Permissions

| Permission | Description |
|------------|-------------|
| `rentabot.use` | Use basic bot commands |
| `rentabot.create` | Create new bots |
| `rentabot.admin` | Access admin commands |
| `rentabot.admin.notify` | Receive update notifications |
| `rentabot.bypass.limit` | Bypass bot limits |
| `rentabot.bypass.cooldown` | Bypass creation cooldown |

## PlaceholderAPI Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%rentabot_count%` | Total bots owned |
| `%rentabot_active%` | Active bots count |
| `%rentabot_stopped%` | Paused bots count |
| `%rentabot_expired%` | Expired bots count |
| `%rentabot_max%` | Max active bots allowed |
| `%rentabot_total%` | Server-wide bot count |
| `%rentabot_price_hour%` | Price per hour |

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

Output: `target/RentABot-1.3.0.jar`

## Changelog

### v1.3.0
- Added comprehensive reload system with subcommands
- Added auto-update checker with download capability
- Fixed config reload not applying immediately
- Improved task rescheduling on reload
- Added hook re-validation
- Better error reporting during reload

### v1.2.0
- Added update checker from GitHub releases
- Improved bot naming system

### v1.1.0
- Added bot lifecycle management (stop/resume)
- Added reserved bot slots
- Improved anti-AFK with randomized intervals

### v1.0.0
- Initial release

## Support

For issues, please create a [GitHub issue](https://github.com/MubTaXim/RentABot/issues) with:
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
