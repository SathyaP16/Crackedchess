Absolutely. The previous response was formatted as explanation **with Markdown snippets inside it**. What you need is the **actual complete contents of `game-controller.md`**, with no extra commentary, so you can copy everything and paste it directly.

````md
# GameController

> **Location:** `src/controller/GameController.java`  
> **Role:** Central Coordinator / Controller / Facade  
> **Primary Responsibility:** Coordinate actions between the GUI, Game Engine, and Networking layers.

---

# 1. Quick Mental Model

`GameController` is the central coordinator between the user interface, networking layer, and game engine.

It receives actions from either:

- the **local player through the GUI**, or
- the **remote player through the network**,

then converts or reconstructs that input as an `Action` and routes it into the `Game`.

The controller does **not** own the board.

The controller does **not** define gameplay rules.

The controller does **not** implement low-level networking.

Its responsibility is coordination.

## Core Flow

### Local Action

```text
BoardView
    ↓
GameController
    ↓
Create Action
    ↓
Game.processAction()
    ↓
Accepted?
    ├── No → Stop
    └── Yes
          ↓
      Broadcast
          ↓
      Opponent
````

### Remote Action

```text
Server / Client
       ↓
GameController
       ↓
Action.deserialize()
       ↓
Game.processAction()
       ↓
Local Game State Updated
```

The key difference is:

> **Local actions may be broadcast. Remote actions must never be broadcast again.**

---

# 2. Role and Responsibility

`GameController` acts as the boundary between three major parts of the application:

```text
                GUI
                 │
                 │ Player Input
                 ▼
        ┌───────────────────┐
        │  GameController   │
        └───────────────────┘
             │         │
             │         │
             ▼         ▼
           Game      Network
             │
             ▼
        RulesEngine
```

Its primary responsibilities are:

* Receive local player input.
* Check whether the local player is allowed to act.
* Convert GUI input into an `Action`.
* Submit actions to the `Game`.
* Broadcast accepted local actions.
* Receive actions from the network.
* Deserialize remote actions.
* Apply remote actions to the local game state.
* Provide legal move information to the GUI.

The controller is therefore a **coordinator**, not the owner of game logic.

---

# 3. Why This Class Exists

Without a controller, the GUI would need to directly interact with multiple parts of the system.

For example:

```text
BoardView
   ├── Validates rules
   ├── Changes game state
   ├── Serializes actions
   └── Sends network messages
```

This would create strong coupling between components.

The GUI would need knowledge about:

* game rules,
* game state,
* networking,
* serialization.

Instead, the architecture becomes:

```text
BoardView
    ↓
GameController
    ↓
Game / Network
```

The GUI only needs to communicate with the controller.

The networking layer does not need to know about the GUI.

The rules engine does not need to know about either.

This creates lower coupling between the components.

As a result, the system becomes easier to:

* test,
* modify,
* extend,
* replace.

For example, a terminal interface could potentially use the same game and controller without changing the game engine.

---

# 4. Architectural Position

`GameController` sits between external events and the game model.

```text
                 LOCAL PLAYER

                      │
                      ▼

                 BoardView
                      │
                      ▼

               GameController
                      │
                      ▼

                    Game
                      │
                      ▼

                RulesEngine
```

Networking follows a similar path:

```text
Remote Player
      │
      ▼
Server / Client
      │
      ▼
GameController
      │
      ▼
    Game
```

The controller provides a common entry point for actions entering the local game instance.

---

# 5. Dependencies

The controller contains four important dependencies.

| Field           | Type     | Purpose                                   | Lifecycle                    |
| --------------- | -------- | ----------------------------------------- | ---------------------------- |
| `game`          | `Game`   | Holds and manages the game state          | Required during construction |
| `server`        | `Server` | Used when the local player hosts the game | Attached later               |
| `client`        | `Client` | Used when the local player joins a game   | Attached later               |
| `localPlayerId` | `int`    | Identifies the local player               | Required during construction |

---

## 5.1 `game`

```java
private final Game game;
```

The `Game` object represents the main game model.

The controller delegates actions to:

```java
game.processAction(action);
```

The controller does not directly modify the board.

It does not manually move pieces.

It does not implement gameplay rules.

Instead:

```text
GameController
       │
       │ Action
       ▼
      Game
       │
       ▼
 RulesEngine
```

The controller coordinates the request.

The game system handles the state transition.

---

## 5.2 `server`

```java
private Server server;
```

This dependency is used when the local machine is hosting the game.

If the controller is acting as the host, accepted actions are sent through:

```java
server.broadcast(serialized);
```

The `Server` handles communication with the remote player.

---

## 5.3 `client`

```java
private Client client;
```

This dependency is used when the local machine joined another player's game.

Accepted local actions are sent through:

```java
client.send(serialized);
```

The controller does not need to know how the client communicates internally.

It only delegates the sending operation.

---

## 5.4 `localPlayerId`

```java
private final int localPlayerId;
```

This identifies which player belongs to this controller.

Current assumption:

```text
0 → White / Host
1 → Black / Guest
```

The controller uses this value to determine whether the local user is currently allowed to submit an action.

For example:

```java
if (game.getCurrentPlayerId() != localPlayerId) {
    return false;
}
```

This prevents the local player from submitting actions during the opponent's turn.

---

# 6. Construction and Initialization

The controller is created with the dependencies required immediately:

```java
public GameController(Game game, int localPlayerId)
```

These values are assigned during construction:

```java
this.game = game;
this.localPlayerId = localPlayerId;
```

The networking dependencies are attached later.

This allows the same controller design to support multiple modes.

## Local or Testing Mode

```text
GameController
    │
    ├── Game
    │
    ├── Server → null
    │
    └── Client → null
```

## Host Mode

```text
GameController
    │
    ├── Game
    │
    └── Server
```

## Guest Mode

```text
GameController
    │
    ├── Game
    │
    └── Client
```

The network dependencies are therefore **late-bound**.

They are not required when the controller is initially created.

They can be attached later through:

```java
attachServer(Server server);
```

or:

```java
attachClient(Client client);
```

---

# 7. Local Player Actions

The controller currently supports three explicit local actions:

```java
onPlayerMove(...)
onPlaceTrap(...)
onCrownTransfer(...)
```

These represent different gameplay mechanics.

However, they follow the same general action pipeline.

```text
1. Check Turn
        ↓
2. Create Action
        ↓
3. Submit Action to Game
        ↓
4. Validate and Process
        ↓
5. Accepted?
        │
   ┌────┴────┐
   │         │
  No        Yes
   │         │
 Stop    Broadcast
```

This is the standard pipeline for local player actions.

---

# 8. Turn Validation

Before processing a local action, the controller checks:

```java
if (game.getCurrentPlayerId() != localPlayerId) {
    return false;
}
```

The purpose is not to determine whether a move is legal.

Instead, it answers a simpler question:

> Is it currently the local player's turn?

If the answer is no:

```text
Player Input
     ↓
Turn Check
     ↓
Not Local Player's Turn
     ↓
Reject Immediately
```

This avoids unnecessary processing.

The controller performs a basic ownership and turn check.

Actual gameplay validation is delegated to the game and rules engine.

---

# 9. Action Creation

Raw GUI input is converted into a standardized `Action`.

For a movement action:

```java
Action action = Action.move(
    localPlayerId,
    from,
    to
);
```

For other mechanics:

```java
Action.placeTrap(...);
```

```java
Action.crownTransfer(...);
```

The GUI therefore does not directly manipulate game state.

Instead:

```text
GUI Input

from = Position
to   = Position

        ↓

      Action

        ↓

Game.processAction()
```

The `Action` acts as a common representation of player intent.

The detailed structure and behavior of actions are documented separately in:

```text
docs/core/action-system.md
```

---

# 10. Delegation to Game

Once an action has been created, the controller sends it to the game:

```java
boolean accepted = game.processAction(action);
```

The controller receives a boolean result:

```text
true
    ↓
Action accepted

false
    ↓
Action rejected
```

The controller does not decide rules such as:

```text
Can this piece move here?

Is the path blocked?

Can a trap be placed here?

Can the crown be transferred?

Does this move violate a game rule?
```

Those decisions belong to the game and rules engine.

The architectural boundary is:

```text
GameController
    ↓
Coordinates actions

Game
    ↓
Processes game state changes

RulesEngine
    ↓
Determines whether actions are legal
```

This prevents `GameController` from becoming a second rules engine.

---

# 11. Broadcasting Accepted Actions

If the action succeeds:

```java
if (accepted) {
    broadcast(action);
}
```

The controller sends the action to the opponent.

The order is important.

```text
Create Action
      ↓
Process Action
      ↓
Accepted?
   ┌──┴──┐
   │     │
  No    Yes
   │     │
 Stop  Broadcast
```

An action is only synchronized after it has been accepted.

This prevents rejected actions from being unnecessarily sent across the network.

---

# 12. Remote Actions

Remote actions enter the controller through:

```java
public void onRemoteAction(String serialized)
```

The action arrives as a serialized network message.

For example:

```text
MOVE|1|1,4|3,4
```

The controller performs two main operations.

---

## Step 1: Deserialize

```java
Action action = Action.deserialize(serialized);
```

The raw network data is reconstructed into an `Action` object.

The flow is:

```text
Serialized String
        ↓
Action.deserialize()
        ↓
Action Object
```

---

## Step 2: Process the Action

The reconstructed action is submitted to:

```java
game.processAction(action);
```

The full flow becomes:

```text
Network Message
       ↓
Deserialize
       ↓
Action Object
       ↓
Game.processAction()
       ↓
Local Game State Updated
```

---

# 13. Why Remote Actions Are Not Broadcast Again

Remote actions deliberately do not call:

```java
broadcast(action);
```

If they did, the same action could continuously bounce between machines.

For example:

```text
Player A
   │
   │ Action
   ▼
Player B
   │
   │ Broadcast Again
   ▼
Player A
   │
   │ Broadcast Again
   ▼
Player B

...
```

Therefore:

## Local Action

```text
Local Input
    ↓
Process
    ↓
Accepted?
    ↓
Broadcast
```

## Remote Action

```text
Network Input
    ↓
Deserialize
    ↓
Process
    ↓
Do Not Broadcast
```

The origin of the action determines whether synchronization occurs.

---

# 14. Querying Legal Moves

The controller also provides a read-only method:

```java
getLegalMovesFor(Position pos)
```

This is primarily used by the GUI to highlight possible destinations when a player selects a piece.

The flow is:

```text
Player Selects Square
        ↓
GameController
        ↓
Find Piece on Board
        ↓
Does Piece Exist?
        │
       No
        ↓
Empty List

       Yes
        ↓
Does Piece Belong to Local Player?
        │
       No
        ↓
Empty List

       Yes
        ↓
RulesEngine.filterLegalMoves()
        ↓
Return Legal Positions
```

The controller first retrieves the board:

```java
Board board = game.getBoard();
```

Then retrieves the piece:

```java
Piece piece = board.getPieceAt(pos);
```

The controller checks:

```java
if (piece == null ||
    piece.getOwnerId() != localPlayerId) {
    return List.of();
}
```

An empty list is returned when:

* there is no piece at the position, or
* the piece belongs to the opponent.

The actual move calculation is delegated to:

```java
RulesEngine.filterLegalMoves(
    board,
    piece,
    game.getPlayer(localPlayerId)
);
```

---

# 15. Command and Query Separation

`GameController` contains two categories of methods.

## Commands

Commands may cause a state change.

Examples:

```java
onPlayerMove(...)
onPlaceTrap(...)
onCrownTransfer(...)
onRemoteAction(...)
```

These methods eventually submit an action through:

```java
game.processAction(...)
```

---

## Queries

Queries retrieve information without intentionally changing the game state.

Example:

```java
getLegalMovesFor(...)
```

It asks:

```text
What legal moves are available?
```

It does not ask:

```text
Perform this move.
```

This separation makes the controller easier to reason about.

---

# 16. The `broadcast()` Helper

All outgoing actions are routed through:

```java
private void broadcast(Action action)
```

This method centralizes network synchronization.

It performs three main steps.

---

## Step 1: Serialize

```java
String serialized = action.serialize();
```

The `Action` object is converted into a format that can be transmitted over the network.

---

## Step 2: Determine the Active Network Connection

If this controller is attached to a server:

```java
server.broadcast(serialized);
```

If this controller is attached to a client:

```java
client.send(serialized);
```

The GUI does not need to know:

```text
Am I the host?

Do I have a Server?

Do I have a Client?

How is this message transmitted?
```

The GUI simply submits an action.

The controller handles the routing decision.

---

# 17. Local or Testing Mode

Both networking dependencies can be `null`.

```text
server == null

client == null
```

In that situation:

```text
Action Processed
       ↓
No Network Connection
       ↓
No Broadcast
```

This allows the controller to operate without an active multiplayer connection.

Possible use cases include:

* local testing,
* single-machine play,
* automated testing,
* game engine development.

---

# 18. Error Handling

Network operations are wrapped in:

```java
try {
    ...
} catch (Exception e) {
    ...
}
```

This prevents certain networking or parsing failures from immediately terminating the application.

The controller handles two external boundaries:

## Outgoing Network Errors

Inside:

```java
broadcast(...)
```

Possible failures include:

* disconnected clients,
* network failures,
* transmission errors.

---

## Incoming Data Errors

Inside:

```java
onRemoteAction(...)
```

Possible failures include:

* malformed serialized actions,
* invalid data formats,
* deserialization failures.

The controller logs the failure:

```text
[GameController] Failed to parse remote action
```

The application can continue instead of allowing malformed input to immediately crash the controller.

---

# 19. Architectural Boundaries

`GameController` should remain a coordinator.

It should not gradually absorb responsibilities belonging to other layers.

## GameController Should Handle

* Player input.
* Action creation.
* Action routing.
* Network synchronization.
* Basic local player and turn checks.
* Read-only queries required by the GUI.

## GameController Should Not Handle

* Piece movement algorithms.
* Pathfinding.
* Complex game rule validation.
* Victory logic.
* Direct board manipulation.
* Low-level socket implementation.
* GUI rendering.

If these responsibilities begin accumulating inside the controller, it risks becoming a **God Object**.

---

# 20. Current Extensibility Model

The controller currently contains explicit entry points for:

```java
onPlayerMove(...)
onPlaceTrap(...)
onCrownTransfer(...)
```

Although these represent different gameplay mechanics, they all follow the same general action pipeline.

```text
MOVE
PLACE_TRAP
CROWN_TRANSFER
       │
       ▼
GameController
       │
       ▼
Action
       │
       ▼
Game.processAction()
       │
       ▼
RulesEngine
```

This creates a stable architectural pipeline.

Future mechanics can follow the same pattern:

```text
NEW FEATURE
     ↓
Controller Entry Point
     ↓
Action
     ↓
Game.processAction()
     ↓
Validation
     ↓
State Update
     ↓
Network Synchronization
```

The architecture documentation does not need to explain the gameplay rules of every mechanic.

Instead, the stable pipeline remains documented here.

The mechanic-specific behavior belongs in the feature documentation.

For example:

```text
docs/features/
├── movement-system.md
├── trap-system.md
├── crown-system.md
└── future-mechanic.md
```

When a new feature is added:

1. Determine whether it fits the existing action pipeline.
2. Add or update its feature documentation.
3. Update core architecture documentation only if the underlying pipeline changes.

This prevents every new feature from requiring a rewrite of the entire documentation system.

---

# 21. Facade-Like Role

`GameController` behaves similarly to a Facade.

The GUI can simply call:

```java
controller.onPlayerMove(from, to);
```

Instead of manually coordinating:

```text
Create Action
    ↓
Check Player Turn
    ↓
Validate Through Game
    ↓
Update State
    ↓
Serialize Action
    ↓
Determine Network Role
    ↓
Send Message
    ↓
Handle Failure
```

The controller provides a simpler interface to a more complex system.

The GUI interacts with one high-level entry point instead of managing several subsystems directly.

---

# 22. Final Mental Model

The simplest way to understand `GameController` is:

```text
                    INPUT
                      │
          ┌───────────┴───────────┐
          │                       │
          ▼                       ▼
         GUI                    NETWORK
          │                       │
          └───────────┬───────────┘
                      ▼
              GameController
                      │
                      ▼
                    Action
                      │
                      ▼
                     Game
                      │
                      ▼
                RulesEngine
                      │
                      ▼
                 Game State
                      │
          ┌───────────┴───────────┐
          │                       │
          ▼                       ▼
      GUI Update            Network Sync
```

The controller sits at the boundary between external input and internal game logic.

It:

1. Receives player or network input.
2. Converts that input into a standard action representation.
3. Delegates processing to the game.
4. Synchronizes accepted local actions when necessary.
5. Provides read-only information required by the GUI.

It does not own the rules.

It does not own the board.

It does not implement networking.

Its responsibility is coordination.

> **GameController receives intent, translates it into actions, delegates execution to the game layer, and coordinates synchronization between players.**

````

That is the full file content. Copy from `# GameController` down to the final line and paste it directly into:

```text
docs/core/game-controller.md
````
