# Lingo Room guest relay

A small Cloudflare Worker that lets a guest join a Lingo Room conversation from a browser over the internet,
with their voice.

- It serves the guest page over https (browsers only allow the microphone on https pages).
- It forwards messages between the host phone and the guest browser. It stores no conversation text or audio.
- All speech recognition and translation still run on the host phone.

The guest page itself is `app/src/main/res/raw/guest_page.html`, the same page the app serves on a local
network. The deploy workflow copies it next to `worker.js`.

## Deploy

1. Create a free Cloudflare account.
2. In the Cloudflare dashboard create an API token with the "Edit Cloudflare Workers" template, and note the
   Account ID.
3. In this GitHub repository add two secrets: `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
4. Run the "Deploy guest relay" workflow.
5. Put the address printed by the workflow in `guest_relay_url` in `app/src/main/res/values/strings.xml`.
