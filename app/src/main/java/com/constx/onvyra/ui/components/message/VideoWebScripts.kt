package com.constx.onvyra.ui.components.message

import org.json.JSONObject

/** Runs in each video frame. Messages only control HTML media; there is no native bridge. */
internal fun videoControlScript(token: String): String {
    val key = JSONObject.quote(token)
    return """
        (() => {
          const key = $key;
          if (window.__rikVideo && window.__rikVideo.key === key) return;
          const state = {key, rate: 1, count: 0, confirmed: false, paused: false, playback: null};
          const children = new Map();
          const primary = () => Array.from(document.querySelectorAll('video, audio')).sort((a, b) =>
            ((b.tagName === 'VIDEO' ? 100000000 : 0) + b.clientWidth * b.clientHeight) -
            ((a.tagName === 'VIDEO' ? 100000000 : 0) + a.clientWidth * a.clientHeight))[0];
          const localPlayback = () => {
            const media = primary();
            if (!media) return null;
            const duration = Number.isFinite(media.duration) && media.duration > 0 ? media.duration : 0;
            let buffered = 0;
            try { if (media.buffered.length) buffered = media.buffered.end(media.buffered.length - 1); } catch (_) {}
            return {hasMedia: true, width: media.videoWidth || 0, height: media.videoHeight || 0,
              position: Number.isFinite(media.currentTime) ? media.currentTime : 0,
              duration, buffered, playing: !media.paused && !media.ended,
              seekable: duration > 0 && media.seekable.length > 0,
              area: media.clientWidth * media.clientHeight, updated: Date.now()};
          };
          const publish = () => {
            const options = [localPlayback(), ...children.values()].filter(Boolean)
              .filter(value => value.updated >= Date.now() - 10000);
            options.sort((a, b) => (b.area || 0) - (a.area || 0) || (b.duration || 0) - (a.duration || 0));
            state.playback = options[0] || null;
            if (window.parent !== window) {
              try { window.parent.postMessage({__rikVideo: key, command: 'playback', playback: state.playback}, '*'); } catch (_) {}
            }
          };
          const target = () => {
            let choice = {frame: null, playback: localPlayback()};
            for (const [frame, playback] of children.entries()) {
              if (playback && (!choice.playback || (playback.area || 0) > (choice.playback.area || 0))) choice = {frame, playback};
            }
            return choice;
          };
          const send = (command) => {
            for (let i = 0; i < window.frames.length; i++) {
              try { window.frames[i].postMessage({__rikVideo: key, command, rate: state.rate}, '*'); } catch (_) {}
            }
          };
          const apply = (command) => {
            let count = 0;
            for (const video of document.querySelectorAll('video, audio')) {
              try {
                video.defaultPlaybackRate = state.rate;
                video.playbackRate = state.rate;
                if (Math.abs(video.playbackRate - state.rate) < 0.01) count++;
                if (state.paused) video.pause();
              } catch (_) {}
            }
            state.count = count;
            state.confirmed = count > 0;
            if (window.parent !== window) {
              try { window.parent.postMessage({__rikVideo: key, command: 'status', count, rate: state.rate, paused: state.paused}, '*'); } catch (_) {}
            }
            return count;
          };
          state.command = (command, rate) => {
            if (typeof rate === 'number' && rate >= 0.25 && rate <= 4) state.rate = rate;
            if (command === 'pause') state.paused = true;
            if (command === 'activate') state.paused = false;
            if (command === 'play') state.paused = false;
            apply(command);
            if (command === 'play') {
              const chosen = target();
              if (chosen.frame) {
                try { chosen.frame.postMessage({__rikVideo: key, command, rate: state.rate}, '*'); } catch (_) {}
              } else {
                const media = primary();
                if (media) { try { const promise = media.play(); if (promise) promise.catch(() => {}); } catch (_) {} }
              }
            } else send(command);
            publish();
          };
          state.seek = (position) => {
            if (!Number.isFinite(position) || position < 0) return;
            const chosen = target();
            if (chosen.frame) {
              try { chosen.frame.postMessage({__rikVideo: key, command: 'seek', position}, '*'); } catch (_) {}
            } else {
              const media = primary();
              if (media && Number.isFinite(media.duration) && media.duration > 0) {
                try { media.currentTime = Math.min(position, media.duration); } catch (_) {}
              }
            }
            publish();
          };
          window.__rikVideo = state;
          window.addEventListener('message', event => {
            const data = event.data;
            if (!data || data.__rikVideo !== key) return;
            if (data.command === 'playback') {
              if (data.playback && data.playback.hasMedia) children.set(event.source, data.playback);
              else children.delete(event.source);
              publish();
              return;
            }
            if (data.command === 'hello') {
              try { event.source.postMessage({__rikVideo: key, command: state.paused ? 'pause' : 'activate', rate: state.rate}, '*'); } catch (_) {}
              return;
            }
            if (data.command === 'status') {
              if (data.count > 0 && Math.abs(data.rate - state.rate) < 0.01) state.confirmed = true;
              if (window.parent !== window) {
                try { window.parent.postMessage(data, '*'); } catch (_) {}
              }
              return;
            }
            if (data.command === 'seek') { state.seek(data.position); return; }
            if (data.command === 'rate' || data.command === 'pause' || data.command === 'activate' || data.command === 'play') state.command(data.command, data.rate);
          });
          document.addEventListener('loadedmetadata', () => apply('rate'), true);
          document.addEventListener('play', () => apply('rate'), true);
          for (const event of ['loadedmetadata', 'durationchange', 'timeupdate', 'progress', 'play', 'playing', 'pause', 'ended', 'seeked']) {
            document.addEventListener(event, publish, true);
          }
          document.addEventListener('DOMContentLoaded', () => state.command('rate', state.rate), {once: true});
          window.addEventListener('load', () => state.command('rate', state.rate), {once: true});
          if (window.parent !== window) {
            try { window.parent.postMessage({__rikVideo: key, command: 'hello'}, '*'); } catch (_) {}
          }
        })();
    """.trimIndent()
}

internal fun videoCommandScript(command: String, speed: Float): String =
    "window.__rikVideo && window.__rikVideo.command(${JSONObject.quote(command)}, $speed);"

internal fun videoSeekScript(positionSeconds: Double): String =
    "window.__rikVideo && window.__rikVideo.seek(${positionSeconds.takeIf { it.isFinite() && it >= 0 } ?: 0.0});"
