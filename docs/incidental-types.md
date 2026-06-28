# Incidental Types — Reference

This is the source of truth for what counts as an **incidental** and the exact
types the AI must classify into. The Groq classification prompt is built from
this document. Keep this in sync with the `INCIDENTAL_COLUMNS` config.

Context: container hauling / trucking logistics, Philippines. Chats are in
**Taglish** (mixed Tagalog + English).

---

## What is an incidental?

An **incidental** is an extra charge that comes up during or around a trip, on
top of the base hauling rate. Most are **pass-through**: a third party (port,
CY/container yard, warehouse, workers) charges *us*, and we bill it back to the
**client**. Others are charges we levy when the client's actions (or inaction)
tie up our truck/chassis or cancel a trip.

Almost every incidental is a **cost** (`isCost: true`). A message is an
incidental if it discusses one of the types below — proposing, reporting,
disputing, or confirming such a charge.

---

## Type list (canonical)

| key                  | header                          | classified by AI? |
|----------------------|---------------------------------|-------------------|
| `bobtail`            | BOBTAIL CHARGES                 | yes               |
| `chassis_rental`     | CHASSIS RENTAL                  | yes               |
| `diversion_fee`      | DIVERSION FEE                   | yes               |
| `truck_demurrage`    | TRUCK DEMURRAGE FEE             | yes               |
| `lolo_charges`       | LOLO CHARGES                    | yes               |
| `safekeeping_charges`| SAFEKEEPING CHARGES             | yes               |
| `storage_fees`       | STORAGE FEES                    | yes               |
| `foul_trip`          | FOUL TRIP                       | yes               |
| `pullout_charges`    | PULLOUT CHARGES                 | yes               |
| `weighing_fee`       | WEIGHING FEE                    | yes               |
| `overtime_charges`   | OVERTIME CHARGES                | yes               |
| `xray_dea_charges`   | XRAY & DEA CHARGES              | yes               |
| `processing_fee`     | PROCESSING FEE                  | yes               |
| `entry_coupon`       | ENTRY COUPON                    | yes               |
| `entry_fee`          | ENTRY FEE                       | yes               |
| `mano_fee`           | MANO FEE                        | yes               |
| `overweight_fee`     | OVERWEIGHT FEE                  | yes               |
| `lalamove_fee`       | LALAMOVE FEE                    | yes               |
| `delivery_permit`    | DELIVERY PERMIT                 | yes               |
| `documentation_fee`  | DOCUMENTATION FEE               | yes               |
| `no_of_days`         | NO. OF DAYS                     | **no — excluded** (a count, not a charge) |
| `other_charges`      | Other Charges (catch-all)       | **no — excluded** |

> `no_of_days` and `other_charges` are excluded from AI classification per
> instruction. They remain in the column config for the spreadsheet/report only.

---

## Definitions

Grouped for readability. The `key` is what the AI must output as
`incidental_type`.

### A. Container return / chassis tie-up
These mostly stem from the client having **no pre-advise** (no instruction on
where/when to return the empty container), which strands our equipment.

- **`bobtail` — Bobtail Charges**
  The chassis (with container) gets detached from the trailer head and left at
  the warehouse; the head has to run without it.

- **`chassis_rental` — Chassis Rental**
  Delivery is complete and the truck head has left, but the empty container is
  stuck on our chassis because the client/shipment has no pre-advise on where to
  return the empty. Our chassis stays tied up holding their empty → billed as
  chassis rental.

- **`truck_demurrage` — Truck Demurrage Fee**
  Connected to chassis rental. Container delivered but no pre-advise, so our
  truck is stuck with the empty instead of moving to another trip. Usually
  charged after ~24 hours from delivery with still no return pre-advise.

- **`diversion_fee` — Diversion Fee**
  Added charge when the pre-advise location for empty return is out of the way —
  too far, or just not a strategic/convenient location to return to.

- **`lolo_charges` — LOLO Charges (Lift-On / Lift-Off)**
  Container is delivered to a temporary grounding place (a CY) to be left there
  for a fee because there's no pre-advise yet. **Lift-off** = container lifted
  off the chassis by the yard's equipment; **Lift-on** = when we return to the
  CY and they put it back on the chassis. These lift charges = LOLO.

- **`safekeeping_charges` — Safekeeping Charges**
  When we send a container to a CY for temporary grounding where that CY is NOT
  the actual pre-advise location (just a temporary holding spot). The CY charges
  us safekeeping, which we bill back to the client.

- **`pullout_charges` — Pullout Charges**
  When our truck fetches the container again from temporary grounding (CY). We
  charge pullout for the fuel and manpower expended.

- **`storage_fees` — Storage Fees**
  Similar to `safekeeping_charges` — charges for a container being stored/held
  at a CY. Heavily overlaps with safekeeping; classify by the exact term used in
  the message ("storage" vs "safekeeping").

### B. Trip disruptions
- **`foul_trip` — Foul Trip**
  Trip assigned and our truck is ready to go, but still waiting on documents or
  confirmation. Client fails to provide docs/confirmation, truck is put on hold,
  and the trip is ultimately cancelled → we charge foul trip.

- **`overtime_charges` — Overtime Charges**
  Our truck arrives at a warehouse and is kept there a long time (usually
  ~24 hours) without anyone assisting to offload the cargo.

### C. Facility / handling charges (pass-through)
Third party charges us; we bill back to client.

- **`weighing_fee` — Weighing Fee**
  Our truck goes to a warehouse/port/CY and is weighed; we get charged, billed
  back to client.

- **`xray_dea_charges` — Xray & DEA Charges**
  Same as weighing fee but for X-ray / DEA scanning at the port/CY.

- **`mano_fee` — Mano Fee**
  Our cargo is offloaded manually ("mano" = by hand) by workers who charge us;
  billed back to client.

- **`overweight_fee` — Overweight Fee**
  Charge for cargo/container exceeding weight limits. (Self-explanatory.)

### D. Documentation & permits (pass-through)
A cluster of similar fees — the CY/port charges us for paperwork/processing,
billed back to client. These are easy to confuse with each other; rely on the
exact wording in the message.

- **`documentation_fee` — Documentation Fee**
  CY or port charges us for documentation; billed back to client. (The base
  case the others are described relative to.)

- **`processing_fee` — Processing Fee**
  Similar to documentation fee — CY/port charges us for additional processing.

- **`entry_coupon` — Entry Coupon**
  Similar to documentation fee.

- **`entry_fee` — Entry Fee**
  Similar to documentation fee.

- **`delivery_permit` — Delivery Permit**
  Similar to documentation fee.

- **`lalamove_fee` — Lalamove Fee**
  For documents the client requires, we send the docs via Lalamove (courier);
  we bill the Lalamove cost back to the client.

---

## Notes for AI classification

- **Taglish input.** Expect mixed Tagalog/English, abbreviations, typos, and
  shorthand. Amounts may appear as `1500`, `1,500`, `php1500`, `1.5k`, etc.
- **Group D is the hardest.** `documentation_fee`, `processing_fee`,
  `entry_coupon`, `entry_fee`, `delivery_permit` overlap heavily — classify by
  the literal term used in the message; only fall back to `documentation_fee`
  when generic.
- **Group A is driven by "no pre-advise" / stuck empty container** language —
  `chassis_rental`, `truck_demurrage`, `diversion_fee`, `lolo_charges`,
  `safekeeping_charges`, `pullout_charges` cluster around the same scenario.
- A single message can mention more than one incidental. (Current schema stores
  one type per row — revisit if multi-type messages are common.)
- If it's an incidental but none of the above types fit, prefer
  `is_incidental: true` with low confidence and a null/best-guess type rather
  than forcing a wrong category.
