# A field guide to the paper lantern

A lantern is a small promise that the evening will be *worth staying out for*. It is paper over
a frame, a candle that **never quite sits straight**, and a knot of wire that someone tied by
`hand` in the afternoon. This guide is written for the reader who has one lantern, a spare
wick, and a market to walk through.

The craft has a home on the web at <https://example.invalid/lanterns> and a mailing list at
[the lantern letter](mailto:lanterns@example.invalid); a bare link such as
https://example.invalid/festival works too. ~~Lanterns are only for festivals.~~ They are for
any evening you decide to keep.

Escaped markers stay literal: \*this is not emphasis\* and \[this is not a link\]. Raw HTML is
shown as the text it is: <span class="wick">a span</span> paints, and nothing runs.

A hard break ends this line  
and this row starts a new one, which is the only break the author asked for.

## What a lantern is made of

- Paper, torn along a fold and never cut.
- Bamboo for the frame, split thin enough to bend.
  - Split bamboo, soaked first.
  - Twine, tied while the bamboo is still wet.
- A candle, or a wick in a cup of oil.
- Patience, which the market sells in small amounts.

1. Soak the bamboo until it bends without a sound.
2. Tie the frame, square first and diagonal second.
3. Stretch the paper while the frame is still damp.
4. Let it dry somewhere the wind cannot reach.
   1. A doorway is enough.
   2. A shelf above the stove is better.

- [x] Paper bought at the market
- [x] Frame tied
- [ ] Candle fitted
- [ ] Lantern hung

> A lantern is a lamp that has agreed to be carried.
>
> > Carry it low, and it lights the ground you are walking on. Carry it high, and it lights the
> > faces of the people walking beside you.

## The numbers of a lantern

| part   | count | notes                                |
| :----- | ----: | :----------------------------------: |
| paper  |     2 | one sheet per side, folded not cut   |
| bamboo |     8 | four uprights, four diagonals        |
| candle |     1 | a stub is enough; a tall candle tips |
| twine  |    12 | knots, not loops                     |

### Fitting the candle

```java
public final class Candle {

    private final int millimetres;

    public Candle(int millimetres) {
        this.millimetres = millimetres;
    }

    public boolean fits(int cupMillimetres) {
        return millimetres <= cupMillimetres - 2; // leave a finger of air
    }
}
```

The market's own record of one lantern, as it is written down for the buyer:

```json
{
  "lantern": "paper",
  "candle": { "millimetres": 60, "scented": false },
  "hung": true,
  "notes": null
}
```

Hanging one takes a hook, a ladder and a second pair of hands:

```sh
hook=/usr/local/bin/hook
if [ -x "$hook" ]; then
    "$hook" --height 2400 --lantern paper
fi
```

A fence whose language is unknown paints exactly as it was written:

```
    kept as written
      indentation and all
```

An indented block does the same without a fence:

    fold along the long edge
    press the crease with a thumbnail
    open it once, and once only

#### A note on the weather

Rain does not put a lantern out; wind does. Paper that has dried too quickly will crack along
the fold, and a lantern that has cracked will burn faster than its candle, which is the only
way a lantern is ever in a hurry. The word for the lantern is 提灯 in Japanese, 灯笼 in
Chinese and 등 in Korean, and none of the three means a lamp you leave at home.

##### A note on the knot

The knot matters more than the paper. A lantern that is tied badly is a lantern you will watch
all evening instead of the market.

###### A note on the market

The market opens when the first lantern is lit and closes when the last one is taken down,
which is either midnight or the moment the candles run out, whichever comes first.

---

![a paper lantern hanging above a stall](lantern.png)

Every lantern in the market is somebody's evening. Walk slowly, and keep the lantern between
you and the middle of the street: the light falls on the ground ahead, the wire keeps it from
turning, and the paper remembers every hand that touched it before yours.

<div class="stall">
  <p>This block is written as HTML in the source, and it is painted as the text above.</p>
</div>
