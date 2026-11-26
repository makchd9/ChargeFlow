#!/usr/bin/env bash
# Rebuilds the PDF/DOCX deliverables from the markdown sources (needs pandoc + Google Chrome).
set -e; cd "$(dirname "$0")"
CHROME=${CHROME:-$(command -v google-chrome || echo "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")}
for pair in "PROPOSAL ChargeFlow_Proposal_2405709 print.css" "REPORT ChargeFlow_Technical_Report_2405709 print.css" "DEMO_EVIDENCE ChargeFlow_Demo_Evidence_2405709 print_evidence.css"; do
  set -- $pair
  pandoc "$1.md" -f gfm -t html5 -s --metadata title=" " --css "$3" --embed-resources --resource-path=. -o "/tmp/$2.html"
  "$CHROME" --headless=new --disable-gpu --no-pdf-header-footer --print-to-pdf="$2.pdf" "/tmp/$2.html"
  [ "$1" = DEMO_EVIDENCE ] || pandoc "$1.md" -f gfm -t docx --resource-path=. -o "$2.docx"
done
