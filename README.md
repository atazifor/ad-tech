
# Introduction to Programmatic Advertising — The ESPN × Nike Story

Every time you open a website like ESPN, something subtle happens that almost no one notices.  
The page loads — headline, story, pictures — but for a split second, one small area on the screen stays blank.  
You might scroll right past it, never realizing that this quiet, empty spot has just **entered an auction**.

While you’re waiting for the article to appear, computers are already talking to each other.  
Behind the scenes, ESPN’s systems are offering that blank space to a marketplace where brands like Nike, Adidas, and Gatorade are competing — in real time — to show you their ad.  
Each brand decides if *you* fit their target audience and how much they’re willing to pay for that single moment of your attention.

All of this happens faster than a blink — usually under 100 milliseconds — long before you finish your first sentence.  
No one at ESPN or Nike is clicking buttons. It’s entirely automated: data in, bids out, winner chosen.

This story unpacks that invisible process — from the moment the page loads to the instant an ad appears — revealing how every “simple” banner on the internet is actually the result of a silent, global auction happening billions of times a day.

## Table of Contents
1. [A sports fan opens ESPN — an ad slot “wakes up”](#panel-1)
2. [SSP screens the slot for safety and policy](#panel-2)
3. [The SSP broadcasts a bid request to DSPs](#panel-3)
4. [Inside a DSP — “should we bid for Nike?”](#panel-4)
5. [Nike's DSP sends back a price and a creative](#panel-5)
6. [Auction closes — Nike wins and the ad is shown](#panel-6)
7. [After the impression — tracking, billing, learning](#panel-7)
8. [Recap Glossary — Key Terms and Their Meanings](#glossary)

---

## <a name="panel-1"></a>1) A sports fan opens ESPN — an ad slot “wakes up”

A user opens an ESPN article on their phone — maybe a recap of last night’s NBA game.  
The page loads normally, but one rectangle on the page is intentionally left empty.  
That empty space is now *inventory* — something the publisher can sell.

```

┌──────────────────────────────┐
│   ESPN ARTICLE PAGE (Mobile) │
│  ───────────────────────────  │
│   [ Headline + Content... ]  │
│                              │
│   [   EMPTY AD SLOT   ]      │  ← This is what will be sold
│                              │
│   [ More article text... ]   │
└──────────────────────────────┘

```

The publisher (ESPN) hands this inventory to its SSP to monetize.

---

## <a name="panel-2"></a>2) SSP screens the slot for safety and policy

Before asking buyers to bid, the SSP enforces rules:

- **Brand Safety** — no extremist, adult, fake news adjacency
- **Relevance / Vertical suitability** — it’s a sports page → sports brands make sense
- **Allow/Block lists** — publisher restrictions (e.g., “no gambling here”)

Only when the slot passes these checks does the SSP proceed to sell it.

---

## <a name="panel-3"></a>3) The SSP broadcasts a bid request to DSPs

The SSP now asks many demand platforms at once:

> “Who wants this ESPN sports impression right now?”

```

```
             ┌────────────────────┐
             │   SSP / Exchange   │
             └─────────┬──────────┘
                       │   broadcasts
     ┌─────────────────┼─────────────────┐
     ▼                 ▼                 ▼
 [ DSP A ]         [ DSP B ]         [ DSP C ]     …many more
```

(Nike inside?)   (Adidas inside?)   (Gatorade?)

```

Each DSP represents many advertisers behind it. Nike lives behind one of these DSPs.

---

## <a name="panel-4"></a>4) Inside a DSP — “Should we bid for Nike?”

The DSP runs through fast decision logic (<50 ms):

- Does this match **Nike’s targeting**? (sports page, mobile, evening)
- Is the **user eligible**? (segment, frequency cap not exceeded)
- Is the **campaign budget still available**?
- Are **policies and consent respected**?

If all pass, DSP estimates value and prepares a bid.

---

## <a name="panel-5"></a>5) Nike's DSP sends back a price and a creative

A **bid response** is sent:

> “We are willing to pay $X and here is the Nike ad to show if we win.”

A creative is the actual ad the user will see — e.g.:

```

+-----------------------------------------+
|  NIKE RUNNING — 30% OFF TODAY ONLY      |
|           [   SHOP NOW   ]              |
+-----------------------------------------+

```

Other DSPs may send lower bids or no bids.

---

## <a name="panel-6"></a>6) Auction closes — Nike wins and the ad is shown

The SSP compares all bids and picks the winner.

```

┌──────────────────────────────┐
│   ESPN ARTICLE PAGE (Mobile) │
│  ───────────────────────────  │
│   [ Headline + Content... ]  │
│                              │
│   [   NIKE AD APPEARS   ]    │  ← This moment = 1 impression
│                              │
│   [ More article text... ]   │
└──────────────────────────────┘

```

Now it is no longer “inventory” — it became a delivered **impression**.

---

## <a name="panel-7"></a>7) After the impression — tracking, billing, learning

Three silent processes start:

1) **Tracking** — impression pixel fires, clicks get logged, conversions may follow
2) **Billing** — Nike pays for delivered impressions, publisher gets paid
3) **Learning** — performance data feeds future bidding logic (what worked, when, where, for whom)

```

Impression
│
┌────┴────┬───────┐
▼         ▼       ▼
Tracking  Billing  Optimization

```

This loop repeats billions of times per day, in under 100 ms each time.

---

## <a name="glossary"></a>Recap Glossary — Key Terms

| Term | Simple Meaning |
|------|----------------|
| **Publisher** | The site/app showing the ad (e.g., ESPN) |
| **SSP** | Supply-Side Platform — sells impressions on behalf of publishers |
| **DSP** | Demand-Side Platform — buys impressions on behalf of advertisers |
| **Creative** | The actual advertisement (image, video, text) shown to the user |
| **Impression** | One actual display of an ad to a user |
| **Bid Request** | SSP → DSP message describing the ad opportunity |
| **Bid Response** | DSP → SSP reply with price + creative to show |
| **Targeting** | Rules about where/when/to whom ads should appear |
| **Segment** | A group of users with shared traits/behaviors (e.g., sports fans) |
| **Brand Safety** | Ensuring ads don’t appear next to harmful or risky content |
| **Allow / Block Lists** | Explicit domains/apps/categories an advertiser allows or forbids |
| **Auction** | The real-time competition between DSPs to buy an impression |

---

## Technical Documentation

This repository implements a production-ready RTB (Real-Time Bidding) engine built in phases. Each phase adds new capabilities:

### Implementation Phases

- **[Phase 1: Basic RTB Bidding](docs/phase1.md)** - Foundation: OpenRTB protocol, Aerospike integration, basic bid response generation
- **[Phase 2: Multi-Campaign Targeting](docs/phase2.md)** - Campaign management, geographic/device targeting, campaign selection algorithms
  - **[Phase 2 Quick Reference](docs/phase2-summary.md)** - API cheat sheet, targeting patterns, performance tips
- **[Phase 3: Budget Tracking](docs/phase3.md)** - Real-time spend tracking, atomic budget operations, auto-pause on depletion
  - **[Phase 3 Quick Reference](docs/phase3-summary.md)** - Budget math, atomic patterns, race condition testing

### Advanced Topics

- **[Tile-Based Geo-Targeting](docs/tiles.md)** - High-performance geo-fencing using H3 hexagonal grids and S2 spherical geometry

### Technology Stack

- **Language**: Java 17 + Spring Boot 3.5
- **Database**: Aerospike (sub-millisecond reads/writes)
- **Geo Libraries**: Uber H3, Google S2 Geometry
- **Protocol**: OpenRTB 2.5

### Quick Start

```bash
# Start Aerospike
docker-compose up -d

# Build and run
mvn clean package
java -jar target/rtb-engine-0.0.1-SNAPSHOT.jar

# Run tests
./test-phase1.sh
./test-phase2.sh
```

