# Product Vision

## Overview

Grocery shopping in a shared household creates a surprising amount of overhead.

Someone buys groceries, someone else needs to figure out which items belong to whom, shared products need to be divided fairly, roommates need to settle up, and eventually someone realizes that the eggs, rice, paper towels, or sandwich bread are gone.

Most applications solve only one piece of this problem. Expense-sharing apps divide money. Grocery-list apps maintain lists. Budgeting apps categorize transactions. Inventory apps require users to manually track what they have.

This product brings those pieces together by learning how a household actually shops.

At its center is a simple idea:

> **The receipt is the source of truth.**

A receipt tells the system what actually entered the household, when it was purchased, how much it cost, and -- after assignment -- who it belonged to. Over time, those receipts create a history of the household's purchasing behavior.

The system learns from that history to make future grocery shopping, splitting, and planning increasingly automatic.

---

## The Core Experience

The primary workflow begins when someone buys groceries.

They photograph or upload the receipt. The system extracts the individual purchases and predicts which household members each item belongs to.

Instead of making someone manually divide an entire receipt, the application focuses their attention on the unusual or uncertain items.

A receipt might become:

**Coke Zero**
TJ -- high confidence

**Paper Towels**
Everyone -- high confidence

**Frozen Pizza**
Andrew -- high confidence

**Fresh Cilantro**
Sumedh + Savar -- needs confirmation

The user reviews anything questionable and confirms the receipt.

The application then calculates everyone's portion and updates the household's balances.

Every correction also teaches the system something about the household.

The next time a similar item appears, the system has more information to work with.

---

## A Household That Learns

The application is not simply memorizing exact products.

It develops a model of the household's purchasing patterns.

If someone frequently buys several related products, the system can use those relationships when encountering something new. Cilantro, serrano peppers, jalapenos, and other cooking ingredients are semantically related even though their receipt descriptions may be completely different.

This allows the application to learn patterns such as:

* products usually purchased by one person;
* products commonly shared by everyone;
* products commonly shared by particular subsets of roommates;
* categories associated with particular household members;
* recurring purchases;
* how frequently particular products or types of products are purchased.

Ownership is also not restricted to one person. An item can belong to any combination of household members.

The result is a model tailored specifically to one household rather than a generic model trying to guess how everyone shops.

---

## Humans Remain the Authority

Automation should reduce work, not create arguments.

Everything the system predicts is a suggestion that can be corrected.

When the system is confident, it can automatically assign an item. When confidence is low, it asks.

The goal is therefore not necessarily to make a prediction for every item. It is to make reliable predictions when enough evidence exists and recognize when human input is more appropriate.

Corrections are valuable rather than exceptional. Each correction becomes another observation that improves future predictions.

The application can also intelligently choose which historical purchases would be most useful for household members to label. Instead of asking someone to categorize hundreds of old items, it can select a small, diverse set that provides the greatest opportunity for the model to learn.

Over time, less manual intervention should be necessary.

---

## Beyond Splitting Receipts

Receipt splitting is the first application of a larger idea.

Once the system understands what enters the household and maintains a history of those purchases, it can begin answering other questions.

### "What are we probably running out of?"

The household should not need to maintain a precise inventory containing statements like "1.7 pounds of rice remaining."

Instead, the system can reason from purchasing behavior.

If sandwich bread is normally purchased every eight days and the last purchase was nine days ago, the system can suggest that the household may be running low.

If Coke Zero was purchased yesterday and is normally purchased every two weeks, there is little reason to suggest it again.

The result is a probabilistic picture of the household's inventory without requiring everyone to constantly update an inventory database.

---

## The Smart Grocery List

The grocery list becomes the natural extension of this household knowledge.

People can still manually add items, but the system can also suggest recurring purchases based on previous behavior.

For example:

**Shopping List**

* Eggs
* Dish soap
* Basmati rice

**You may also need**

* Sandwich bread -- usually purchased every 8 days
* Paper towels -- last purchased 24 days ago

The system should primarily reason about products and categories the household already uses rather than constantly recommending unfamiliar products.

Its purpose is to speed up grocery shopping, not become an advertising or product-discovery system.

And because the receipt represents what was actually purchased, the system can determine whether its suggestions were correct without relying solely on users manually checking items off a list.

This closes the loop:

**Purchase history -> prediction -> grocery list -> shopping trip -> receipt -> updated history**

---

## Food Expiration

For appropriate products, the system can also estimate when food may expire.

These estimates remain conservative suggestions rather than authoritative claims.

Combined with purchasing history, expiration information could help the household identify food that should be used soon and avoid unnecessary duplicate purchases.

Eventually the application could answer questions such as:

* What might we be running low on?
* What should probably be used soon?
* What do we repeatedly buy?
* What did we buy last time?
* What belongs on this week's grocery list?

All of these emerge from the same underlying household history.

---

## Shared Finances

Once receipt items have owners, splitting the expense becomes straightforward.

The system knows:

* who paid;
* what was purchased;
* who each item belonged to;
* which items were shared.

It can calculate each person's responsibility and maintain balances between household members.

Rather than requiring immediate payment after every grocery trip, balances can be accumulated and simplified so that the household can settle with fewer transactions.

Payment services such as Venmo can eventually be integrated into the final step. The product does not need to become a payment network itself -- it simply needs to make settling the calculated balance convenient.

---

## People, Households, and Accounts

A **person** in the household should not initially need to be a registered user.

Someone can create a household containing:

* Sumedh
* Savar
* Andrew
* TJ

The application can immediately begin associating purchases and learning behavior for those people.

This keeps onboarding extremely lightweight and makes the core experience possible before building substantial account infrastructure.

Later, a person could be linked to an actual account, allowing multiple household members to use the application from their own phones, collaboratively manage grocery lists, review transactions, and confirm purchases.

The product can therefore begin as a simple household tool while retaining a path toward a true multi-user application.

---

## Product Principles

### Receipts are ground truth

Lists, predictions, and inventory estimates represent what the system believes. Receipts represent what actually happened.

### Automate confidence, surface uncertainty

High-confidence decisions should disappear into the background. Low-confidence decisions should be easy for a human to resolve.

### Learn the household, not the average consumer

The valuable information is not that people generally buy milk. It is how *this particular household* buys milk.

### Corrections improve the product

A correction should immediately become useful historical information rather than merely fixing one transaction.

### Avoid unnecessary manual tracking

The application should infer state whenever possible rather than creating another system household members must constantly maintain.

### Suggestions, not commands

Predicted ownership, inventory levels, expiration dates, and shopping-list additions should always remain understandable and overridable.

### Start with groceries

General-purpose check splitting is possible later, but recurring household grocery purchases provide the repeated behavior necessary for the system to actually learn.

---

## Product Roadmap

The first milestone is intentionally narrow:

**Scan a receipt, identify its items, assign those items to household members, and split the expense correctly.**

That creates the foundation and begins generating the data required for everything else.

The next layer makes assignment increasingly automatic by learning from confirmed purchases and corrections.

From there, purchase history becomes persistent household knowledge. That enables recurring-item detection, inventory estimation, and intelligent grocery-list suggestions.

Expiration awareness can add another layer of information about what the household already owns.

Multi-user accounts and payment integrations can come later. They improve collaboration and convenience but are not prerequisites for proving the central idea.

---

## Technical Foundation

The product only needs a few major intelligent components.

A receipt-processing system converts receipt images into structured purchases. Semantic representations allow the system to understand that differently named products may still be related. A household-specific machine-learning model learns ownership patterns from confirmed purchases.

The model preserves uncertainty rather than forcing every item into a confident prediction. Historical examples can be selected intelligently for human labeling so that a relatively small amount of feedback teaches the model as much as possible.

The rest of the system should remain conventional: persistent household and purchase data, deterministic financial calculations, and a mobile-first interface.

The intelligence should support the product rather than become the product.

---

## Long-Term Vision

The long-term goal is an application that quietly understands the rhythm of a household.

Someone goes grocery shopping and scans the receipt.

The application already knows how most of it should be divided.

It updates everyone's balances.

It learns what was purchased.

It recognizes recurring products.

A week later, it notices that something is probably running low and places it among the suggested items for the next shopping trip.

The next receipt tells it whether that prediction was correct.

Every shopping trip makes the system slightly more familiar with the household.

What begins as a receipt splitter gradually becomes a **shared memory for how a household shops** -- reducing the repetitive work of figuring out who bought what, who owes whom, what is already at home, and what needs to be bought next.
