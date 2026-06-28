// REAL messages transcribed from the TVL X BEST group chat screenshots
// (2026-06-27 batch). Chronological-ish. These test the classifier on actual
// dispatch chatter, where incidentals are usually discussed WITHOUT an amount
// and buried in operational noise.
//
// `expect` is a loose guide for eyeballing, not a strict assertion.

export const CASES = [
  // ---- REAL INCIDENTAL DISCUSSIONS ---------------------------------------
  {
    name: "REAL: rent chassis + bobtail + empty not returned (multi-type)",
    history: [
      { sender: "Emmz", message: "Paupdatening po pag truck nsa oparan n bukas after pull out ng empty" },
    ],
    sender: "Victoria",
    message: "Good evening!!! Ms. @Emmz F. Gascon sir James, this is to advise that we were able to rent chassis for our export positioning tomorrow because we need to bobtail our export today, and the empty of our import booking were not able to return due to booking slot issue at Seacon and No allocation at NCT for ONE SL",
    expect: { is_incidental: true, incidental_type: "chassis_rental" },
  },
  {
    name: "REAL: SimplyBook update to avoid Det (detention) charges",
    history: [
      { sender: "Eihzel", message: "1. SIMPLYBOOK REF #: 0001123745  3. Container Numbers: [TGBU5519273]  5. Shipping Line: YANGMING  8. Booking Type: RETURN" },
    ],
    sender: "Eihzel",
    message: "10. Reason for Update: No earliest slot and we have next trip today & also to avoid accumulating Det charges. 11. Details to Update: From 06/26 1600H To 06/25 1300H",
    expect: { is_incidental: true, incidental_type: "truck_demurrage" },
  },
  {
    name: "REAL: diversion not registered for ONE SL",
    history: [
      { sender: "Ronica", message: "@Eihzel Lucero Casas maam pasuyo po sa ONE not register padaw po" },
      { sender: "Victoria", message: "Mam, mas ok if maayos natin yan mam for future use mas advantage pa din ang may option tayo sa mga return" },
    ],
    sender: "Emmz",
    message: "anu po diversion nyo mam, nkaregister kmi jan",
    expect: { is_incidental: true, incidental_type: "diversion_fee" },
  },
  {
    name: "REAL: lalamove the reuse seal to save cost",
    history: [
      { sender: "Eihzel", message: "Opo mip pareho Hts - cpip Prima- ftp" },
    ],
    sender: "Emmz",
    message: "@Emmz F. Gascon maam baka po pwde sa office na lang po namin ipa lalamove selyo ng reuse para medyo mura pa po",
    expect: { is_incidental: true, incidental_type: "lalamove_fee" },
  },
  {
    name: "REAL: will receive seal via lalamove",
    history: [],
    sender: "Jerico",
    message: "magrereceive po ng selyo via lalamove",
    expect: { is_incidental: true, incidental_type: "lalamove_fee" },
  },
  {
    name: "REAL: empty not returned, long queue at ILT (situation, maybe demurrage)",
    history: [
      { sender: "Eihzel", message: "Good day mam. Mam papunta nadin po ung maghustling?" },
    ],
    sender: "Ronica",
    message: "Negative pa mam at hindi pa nakasauli grabe ang haba ng pila sa ILT kagabi pa cla doon, grabe ang usad ngayon mabagal pa sa pagong",
    expect: { is_incidental: true, incidental_type: "chassis_rental" },
  },

  // ---- TRAP: contains an incidental KEYWORD but is NOT a charge -----------
  {
    name: "TRAP: 'naipullout' = wrong container pulled out (NOT a pullout charge)",
    history: [
      { sender: "Emmz", message: "Oparan June 27 1x40hc reuse pick up empty at harbor center FFAU4308210" },
      { sender: "Emmz", message: "Iba ang nkuha container number s rigging" },
    ],
    sender: "Ronica",
    message: "Mali po container na naipullout maam",
    expect: { is_incidental: false },
  },

  // ---- OPERATIONAL NOISE: should be NOT incidental -----------------------
  {
    name: "NOISE: morning roll call",
    history: [],
    sender: "Victoria",
    message: "good morning everyone.... ano ang next trip ng ating mga truck pambukas?",
    expect: { is_incidental: false },
  },
  {
    name: "NOISE: gatepass sent to email",
    history: [],
    sender: "Mary",
    message: "sent na po gatepass sa email maam.",
    expect: { is_incidental: false },
  },
  {
    name: "NOISE: asking truck location",
    history: [],
    sender: "Jerico",
    message: "san na po truck ng oparan",
    expect: { is_incidental: false },
  },
  {
    name: "NOISE: requesting consignee address",
    history: [],
    sender: "Victoria",
    message: "Mam makisuyo ng address nitong HTS",
    expect: { is_incidental: false },
  },
  {
    name: "NOISE: dispatch template / job sheet",
    history: [],
    sender: "Ronica",
    message: "EXPORT TVL TRUCKING SERVICES DRIVER: R.resilva HELPER: R Aquino PLATE NO: jaf9814 DATE: 06-26-26 CHASSIS#:tvl#10 SIZE:1X40, CLIENT: oparan ADDRESS: fill invest calamba laguna CNTR NO:txgu5040257 SEAL NO:ksc622178 PEZA IN:1440H 6/26/26",
    expect: { is_incidental: false },
  },
  {
    name: "NOISE: booking slot / allocation talk",
    history: [],
    sender: "Ronica",
    message: "over allocation po para sa HTS natin sa NCT wala din po slot sa SEACON",
    expect: { is_incidental: false },
  },
  {
    name: "NOISE: confirming reuse / pre-advise",
    history: [],
    sender: "Emmz",
    message: "Opo bukas po loading nyan mam s oparan, reuse song trading",
    expect: { is_incidental: false },
  },
];
