const pptxgen = require('pptxgenjs');
const pptx = new pptxgen();
pptx.layout = 'LAYOUT_WIDE';
pptx.author = 'OpenAI';
pptx.subject = 'Java producer-consumer media upload simulation';
pptx.title = 'Media Upload Simulation — Java Implementation';
pptx.company = 'OpenAI';
pptx.lang = 'en-US';
pptx.theme = {
  headFontFace: 'Aptos Display',
  bodyFontFace: 'Aptos',
  lang: 'en-US'
};
pptx.defineSlideMaster({
  title: 'MASTER',
  background: { color: '0B1220' },
  objects: [
    { line: { x: 0.45, y: 7.12, w: 12.43, h: 0, line: { color: '27354B', width: 0.7 } } },
    { text: { text: 'JAVA CONCURRENCY  /  MEDIA UPLOAD SYSTEM', options: { x: 0.5, y: 7.18, w: 6.5, h: 0.16, fontFace: 'Aptos', fontSize: 7, color: '7E90A9', charSpacing: 1.2, margin: 0 } } },
    { text: { text: 'NOV 2026', options: { x: 11.8, y: 7.18, w: 1.0, h: 0.16, align: 'right', fontFace: 'Aptos', fontSize: 7, color: '7E90A9', charSpacing: 1.2, margin: 0 } } }
  ],
  slideNumber: { x: 12.95, y: 7.16, color: '7E90A9', fontFace: 'Aptos', fontSize: 8 }
});

const C = {
  bg: '0B1220', panel: '121D2D', panel2: '17263A', line: '2A3B52',
  white: 'F3F7FD', muted: 'A7B6C9', cyan: '67B8FF', green: '55D6B0',
  yellow: 'F1C56B', red: 'FF858B', purple: 'B49BFF', dark: '08111E'
};
const S = pptx.ShapeType;

function text(slide, value, x, y, w, h, size=14, color=C.white, opts={}) {
  slide.addText(value, {
    x, y, w, h, fontFace: opts.fontFace || 'Aptos', fontSize: size,
    color, margin: opts.margin === undefined ? 0 : opts.margin,
    breakLine: false, bold: !!opts.bold, italic: !!opts.italic,
    align: opts.align || 'left', valign: opts.valign || 'mid',
    fit: 'shrink', paraSpaceAfterPt: 0, charSpacing: opts.charSpacing || 0,
    bullet: opts.bullet, transparency: opts.transparency || 0,
    isTextBox: true
  });
}
function box(slide, x, y, w, h, fill=C.panel, line=C.line, radius=true) {
  slide.addShape(radius ? S.roundRect : S.rect, {
    x, y, w, h, rectRadius: 0.12,
    fill: { color: fill }, line: { color: line, width: 1 }
  });
}
function pill(slide, label, x, y, w, fill, color=C.dark) {
  slide.addShape(S.roundRect, { x, y, w, h: 0.28, rectRadius: 0.12, fill: { color: fill }, line: { color: fill, transparency: 100 } });
  text(slide, label, x+0.04, y+0.01, w-0.08, 0.24, 8, color, {bold:true, align:'center'});
}
function line(slide, x1, y1, x2, y2, color=C.cyan, width=1.5, arrow=true) {
  slide.addShape(S.line, { x: x1, y: y1, w: x2-x1, h: y2-y1, line: { color, width, endArrowType: arrow ? 'triangle' : 'none' } });
}
function section(slide, kicker, titleText, subtitle='') {
  text(slide, kicker.toUpperCase(), 0.62, 0.36, 4.5, 0.24, 9, C.cyan, {bold:true, charSpacing:1.8});
  text(slide, titleText, 0.62, 0.68, 12.0, 0.5, 26, C.white, {bold:true, fontFace:'Aptos Display'});
  if (subtitle) text(slide, subtitle, 0.64, 1.23, 11.9, 0.32, 11, C.muted);
}
function labelValue(slide, label, value, x, y, w) {
  text(slide, label.toUpperCase(), x, y, w, 0.18, 8, C.muted, {bold:true, charSpacing:1});
  text(slide, value, x, y+0.22, w, 0.35, 17, C.white, {bold:true});
}

// 1 — Cover
{
  const slide = pptx.addSlide();
  slide.background = { color: C.bg };
  slide.addShape(S.ellipse, { x: 8.55, y: 0.0, w: 4.75, h: 4.75, fill: { color: '143457', transparency: 38 }, line: { color: '143457', transparency: 100 } });
  slide.addShape(S.ellipse, { x: 10.15, y: 3.55, w: 3.15, h: 3.15, fill: { color: '0D665E', transparency: 55 }, line: { color: '0D665E', transparency: 100 } });
  pill(slide, 'JAVA 21  •  gRPC  •  CONCURRENT I/O', 0.72, 0.72, 2.9, C.cyan);
  text(slide, 'Media Upload\nSimulation', 0.72, 1.55, 6.9, 1.72, 37, C.white, {bold:true, fontFace:'Aptos Display'});
  text(slide, 'A simple producer–consumer system with bounded queueing,\nnetwork streaming, compression, and a browser gallery.', 0.76, 3.55, 6.2, 0.75, 16, C.muted);
  // Graphic pipeline
  box(slide, 7.55, 1.35, 1.52, 1.05, '14263B', '315274');
  text(slide, 'PRODUCER', 7.68, 1.56, 1.25, 0.25, 12, C.cyan, {bold:true, align:'center'});
  text(slide, 'p threads', 7.68, 1.92, 1.25, 0.2, 9, C.muted, {align:'center'});
  line(slide, 9.12, 1.88, 9.85, 1.88, C.cyan, 2);
  box(slide, 9.95, 1.35, 1.52, 1.05, '152A38', '2F665F');
  text(slide, 'gRPC', 10.08, 1.57, 1.25, 0.25, 14, C.green, {bold:true, align:'center'});
  text(slide, 'chunk stream', 10.08, 1.92, 1.25, 0.2, 9, C.muted, {align:'center'});
  line(slide, 11.52, 1.88, 12.0, 1.88, C.green, 2);
  box(slide, 11.96, 1.35, 0.9, 1.05, '29253A', '5B4D80');
  text(slide, 'QUEUE', 12.02, 1.58, 0.78, 0.22, 10, C.purple, {bold:true, align:'center'});
  text(slide, 'q slots', 12.02, 1.91, 0.78, 0.18, 8, C.muted, {align:'center'});
  line(slide, 12.42, 2.48, 12.42, 3.05, C.purple, 1.5);
  box(slide, 9.95, 3.15, 2.9, 1.16, '142B2B', '28645A');
  text(slide, 'CONSUMER WORKERS', 10.12, 3.35, 2.55, 0.24, 12, C.green, {bold:true, align:'center'});
  text(slide, 'c workers  •  FFmpeg', 10.12, 3.75, 2.55, 0.2, 9, C.muted, {align:'center'});
  line(slide, 11.4, 4.35, 11.4, 4.9, C.green, 1.5);
  box(slide, 9.95, 5.0, 2.9, 0.95, '1A2435', '40516A');
  text(slide, 'VIDEO GALLERY', 10.12, 5.2, 2.55, 0.25, 12, C.white, {bold:true, align:'center'});
  text(slide, 'hover preview  /  click to play', 10.12, 5.57, 2.55, 0.18, 8, C.muted, {align:'center'});
  labelValue(slide, 'p', '3 producers', 0.8, 5.35, 1.6);
  labelValue(slide, 'c', '2 workers', 2.8, 5.35, 1.6);
  labelValue(slide, 'q', '4 admission slots', 4.8, 5.35, 2.3);
  text(slide, 'Illustrative configuration — values are read from config.properties.', 0.8, 6.22, 6.4, 0.28, 9, C.muted, {italic:true});
}

// 2 — Architecture
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '01 / SYSTEM OVERVIEW', 'Two programs, one upload pipeline', 'The producer and consumer are separate Java applications running on different machines or VMs.');

  box(slide, 0.62, 1.82, 4.0, 4.62, '101D2D', '315274');
  pill(slide, 'VM 1', 0.88, 2.05, 0.68, C.cyan);
  text(slide, 'PRODUCER', 0.9, 2.48, 2.5, 0.32, 17, C.white, {bold:true});
  text(slide, 'p independent threads', 0.9, 2.88, 2.9, 0.22, 10, C.muted);
  for (let i=0;i<3;i++) {
    const yy = 3.35 + i*0.72;
    box(slide, 0.9, yy, 3.38, 0.52, '172B40', '35516D');
    text(slide, `producer-${i+1}/`, 1.08, yy+0.08, 1.4, 0.22, 10, C.cyan, {bold:true});
    text(slide, `Thread ${i+1}`, 3.1, yy+0.08, 0.92, 0.22, 9, C.muted, {align:'right'});
  }
  text(slide, 'Check extension + size  →  SHA-1  →  upload_id', 0.92, 5.72, 3.4, 0.34, 9, C.muted);

  box(slide, 4.95, 1.82, 3.05, 4.62, '142735', '326D6A');
  pill(slide, 'TCP / HTTP/2', 5.2, 2.05, 1.18, C.green);
  text(slide, 'gRPC', 5.22, 2.56, 2.3, 0.38, 22, C.green, {bold:true});
  text(slide, 'BeginUpload', 5.22, 3.18, 2.45, 0.24, 11, C.white, {bold:true});
  text(slide, 'Reserve a queue slot first', 5.22, 3.46, 2.45, 0.28, 9, C.muted);
  text(slide, 'UploadVideo(stream)', 5.22, 4.14, 2.45, 0.24, 11, C.white, {bold:true});
  text(slide, 'Send the video in chunks', 5.22, 4.42, 2.45, 0.28, 9, C.muted);
  text(slide, 'CancelUpload', 5.22, 5.1, 2.45, 0.24, 11, C.white, {bold:true});
  text(slide, 'Cancel a pending upload', 5.22, 5.38, 2.45, 0.28, 9, C.muted);

  box(slide, 8.34, 1.82, 4.35, 4.62, '111F2C', '28645A');
  pill(slide, 'VM 2', 8.6, 2.05, 0.68, C.green);
  text(slide, 'CONSUMER', 8.6, 2.48, 2.4, 0.32, 17, C.white, {bold:true});
  box(slide, 8.6, 3.03, 3.8, 0.68, '202B3B', '4D607A');
  text(slide, 'Bounded FIFO queue  •  capacity q', 8.78, 3.25, 3.42, 0.2, 10, C.yellow, {bold:true, align:'center'});
  line(slide, 10.5, 3.74, 10.5, 4.0, C.green, 1.4);
  for (let i=0;i<2;i++) {
    const xx = 8.64 + i*1.98;
    box(slide, xx, 4.06, 1.82, 0.64, '18332F', '2D7062');
    text(slide, `Worker ${i+1}`, xx+0.12, 4.26, 1.58, 0.2, 10, C.green, {bold:true, align:'center'});
  }
  line(slide, 10.5, 4.75, 10.5, 5.0, C.green, 1.4);
  box(slide, 8.6, 5.08, 3.8, 0.88, '1B2938', '41556D');
  text(slide, 'Compressed MP4 storage', 8.78, 5.28, 3.42, 0.2, 11, C.white, {bold:true, align:'center'});
  text(slide, 'HTTP gallery: list • preview • play • delete', 8.78, 5.62, 3.42, 0.18, 8, C.muted, {align:'center'});

  line(slide, 4.64, 4.0, 4.92, 4.0, C.cyan, 1.8);
  text(slide, 'gRPC', 4.55, 3.65, 0.55, 0.18, 8, C.cyan, {bold:true, align:'center'});
  text(slide, 'The browser uses HTTP on the consumer VM; producer uploads use gRPC.', 1.0, 6.63, 11.2, 0.22, 10, C.muted, {italic:true, align:'center'});
}

// 3 — Producer concepts
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '02 / PRODUCER', 'One folder per producer thread', 'Producer concurrency is straightforward: each thread scans a different directory and uploads its own files.');
  const lanes = [
    {n:'THREAD 1', folder:'producer-1/', files:'clip-a.mp4  •  clip-b.mov'},
    {n:'THREAD 2', folder:'producer-2/', files:'clip-c.mkv  •  clip-d.mp4'},
    {n:'THREAD 3', folder:'producer-3/', files:'clip-e.avi  •  clip-f.mp4'}
  ];
  lanes.forEach((item, i) => {
    const yy = 1.88 + i*1.25;
    box(slide, 0.68, yy, 4.15, 0.94, '121F30', '2E4B6B');
    pill(slide, item.n, 0.9, yy+0.16, 0.78, C.cyan);
    text(slide, item.folder, 1.85, yy+0.13, 1.55, 0.23, 12, C.white, {bold:true});
    text(slide, item.files, 1.85, yy+0.48, 2.7, 0.18, 9, C.muted);
    line(slide, 4.9, yy+0.46, 5.62, yy+0.46, C.cyan, 1.7);
  });
  box(slide, 5.75, 1.88, 3.0, 3.69, '132737', '346E73');
  text(slide, 'Per-file checks', 6.02, 2.12, 2.45, 0.3, 17, C.white, {bold:true});
  const checks = [
    ['01','Exists + readable'], ['02','Extension in allow-list'], ['03','File size within limit'], ['04','Compute SHA-1'], ['05','Generate one upload_id']
  ];
  checks.forEach((c,i)=>{
    const yy=2.67+i*0.52;
    pill(slide,c[0],6.02,yy,0.38,C.green);
    text(slide,c[1],6.54,yy+0.02,1.95,0.22,10,C.white);
  });
  line(slide, 8.82, 3.72, 9.45, 3.72, C.green, 1.8);
  box(slide, 9.58, 1.88, 3.0, 3.69, '18263A', '465B7A');
  text(slide, 'gRPC client', 9.86, 2.12, 2.4, 0.3, 17, C.white, {bold:true});
  text(slide, 'BeginUpload', 9.86, 2.75, 2.4, 0.23, 12, C.cyan, {bold:true});
  text(slide, 'Ask for admission before sending bytes.', 9.86, 3.06, 2.3, 0.43, 9, C.muted);
  text(slide, 'UploadVideo', 9.86, 3.72, 2.4, 0.23, 12, C.cyan, {bold:true});
  text(slide, 'Stream small VideoChunk messages.', 9.86, 4.03, 2.3, 0.43, 9, C.muted);
  text(slide, 'Retry', 9.86, 4.68, 2.4, 0.23, 12, C.yellow, {bold:true});
  text(slide, 'Reuse the same upload_id.', 9.86, 4.98, 2.3, 0.25, 9, C.muted);
  box(slide, 0.68, 6.0, 11.9, 0.64, '172A3F', '314967');
  text(slide, 'Key idea', 0.92, 6.2, 1.0, 0.2, 10, C.cyan, {bold:true});
  text(slide, 'p = number of parallel folder readers — not the number of files or network connections.', 2.0, 6.19, 10.1, 0.22, 11, C.white);
}

// 4 — gRPC / chunks
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '03 / NETWORK PROTOCOL', 'Admission first, bytes second', 'A two-call upload protocol makes queue-full feedback immediate and keeps large files out of one huge message.');
  const xA=0.8, xB=6.85, w=2.6;
  text(slide, 'PRODUCER CLIENT', xA, 1.83, w, 0.25, 12, C.cyan, {bold:true, align:'center'});
  text(slide, 'CONSUMER SERVER', xB, 1.83, w, 0.25, 12, C.green, {bold:true, align:'center'});
  line(slide, 2.1, 2.3, 2.1, 6.0, '45617C', 1.2, false);
  line(slide, 8.15, 2.3, 8.15, 6.0, '45617C', 1.2, false);
  // Begin / response
  line(slide, 2.1, 2.65, 8.15, 2.65, C.cyan, 1.8);
  text(slide, 'BeginUpload(upload_id, filename, SHA-1, size)', 2.35, 2.34, 5.6, 0.22, 10, C.white, {align:'center'});
  line(slide, 8.15, 3.25, 2.1, 3.25, C.green, 1.6);
  text(slide, 'ACCEPTED + stream_token   OR   QUEUE_FULL', 2.35, 3.34, 5.6, 0.22, 10, C.yellow, {bold:true, align:'center'});
  // Chunks
  line(slide, 2.1, 4.05, 8.15, 4.05, C.cyan, 1.8);
  text(slide, 'UploadVideo: chunk 0  →  chunk 1  →  chunk 2  →  ...', 2.35, 3.75, 5.6, 0.22, 10, C.white, {align:'center'});
  line(slide, 2.1, 4.65, 8.15, 4.65, C.cyan, 1.8);
  text(slide, 'Last chunk sent; stream completes', 2.35, 4.76, 5.6, 0.22, 9, C.muted, {align:'center'});
  line(slide, 8.15, 5.35, 2.1, 5.35, C.green, 1.6);
  text(slide, 'QUEUED + video_id   (or FAILED)', 2.35, 5.45, 5.6, 0.22, 10, C.green, {bold:true, align:'center'});

  box(slide, 9.55, 2.05, 2.95, 3.55, '172437', '34536E');
  text(slide, 'Why chunks?', 9.82, 2.32, 2.4, 0.32, 16, C.white, {bold:true});
  text(slide, 'A video is read in small blocks (for example, 64 KB) instead of loading the entire file into memory.', 9.82, 2.85, 2.35, 1.0, 10, C.muted);
  pill(slide, 'STREAM TOKEN', 9.82, 4.08, 1.08, C.purple);
  text(slide, 'Identifies one network stream. A retry with the same upload_id gets a fresh token; old callbacks cannot cancel the new stream.', 9.82, 4.48, 2.35, 0.82, 9, C.white);
  text(slide, 'The producer calculates SHA-1; the consumer recalculates it after receiving all bytes.', 0.9, 6.25, 11.6, 0.24, 10, C.muted, {italic:true, align:'center'});
}

// 5 — Queue details
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '04 / QUEUEING', 'A bounded FIFO queue with drop-tail rejection', 'The semaphore reserves capacity before upload; the blocking queue preserves first-in, first-out processing.');
  box(slide, 0.72, 1.88, 2.3, 3.9, '13243A', '345B7D');
  text(slide, 'INCOMING', 0.95, 2.18, 1.82, 0.28, 14, C.cyan, {bold:true, align:'center'});
  for (let i=0;i<3;i++) {
    const yy=2.78+i*0.75;
    box(slide, 1.03, yy, 1.62, 0.52, '1C334B', '3D5F83');
    text(slide, `Video ${String.fromCharCode(65+i)}`, 1.15, yy+0.15, 1.38, 0.19, 10, C.white, {bold:true, align:'center'});
  }
  line(slide, 3.08, 3.75, 3.72, 3.75, C.cyan, 1.8);
  box(slide, 3.82, 1.88, 5.3, 3.9, '171F31', '4D4775');
  text(slide, 'ADMISSION SLOTS  q = 4', 4.12, 2.17, 4.7, 0.28, 14, C.purple, {bold:true, align:'center'});
  for (let i=0;i<4;i++) {
    const xx=4.15+(i%2)*2.2, yy=2.76+Math.floor(i/2)*1.0;
    box(slide, xx, yy, 1.88, 0.72, '26263D', '655A91');
    text(slide, `Slot ${i+1}`, xx+0.12, yy+0.12, 1.64, 0.18, 9, C.muted, {align:'center'});
    text(slide, i===0?'Video A':i===1?'Video B':i===2?'Video C':'Video D', xx+0.12, yy+0.36, 1.64, 0.2, 11, C.white, {bold:true, align:'center'});
  }
  pill(slide, 'FULL → REJECT', 5.38, 4.94, 1.35, C.red);
  line(slide, 9.2, 3.75, 9.75, 3.75, C.green, 1.8);
  box(slide, 9.85, 1.88, 2.75, 3.9, '102A2B', '2D6A60');
  text(slide, 'c = 2 WORKERS', 10.08, 2.17, 2.3, 0.28, 14, C.green, {bold:true, align:'center'});
  for (let i=0;i<2;i++) {
    const yy=2.85+i*1.14;
    box(slide, 10.2, yy, 2.0, 0.78, '1A3B36', '347E70');
    text(slide, `Worker ${i+1}`, 10.35, yy+0.16, 1.7, 0.2, 11, C.white, {bold:true, align:'center'});
    text(slide, 'compress one video', 10.35, yy+0.44, 1.7, 0.16, 8, C.muted, {align:'center'});
  }
  text(slide, 'Queue slot is released when a worker takes a job — not when FFmpeg finishes.', 1.05, 6.03, 11.2, 0.25, 12, C.white, {bold:true, align:'center'});
  text(slide, 'Terminology: this is the specification’s fixed-capacity/drop-on-full behavior (bounded drop-tail FIFO), not a rate-shaped textbook leaky bucket.', 0.85, 6.42, 11.6, 0.2, 8, C.muted, {italic:true, align:'center'});
}

// 6 — Consumer and GUI
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '05 / CONSUMER', 'Workers turn accepted uploads into gallery videos', 'The consumer handles receiving, integrity checks, compression, persistent storage, and browser delivery.');
  const steps = [
    {x:0.75, title:'TEMP FILE', body:'Receive chunks\nCheck size + SHA-1', color:C.cyan},
    {x:3.25, title:'FIFO QUEUE', body:'Wait for a worker\nKeep order', color:C.purple},
    {x:5.75, title:'FFMPEG', body:'c worker threads\nRe-encode to MP4', color:C.green},
    {x:8.25, title:'STORAGE', body:'Atomic move\nWrite metadata', color:C.yellow},
    {x:10.75, title:'GALLERY', body:'10s hover preview\nClick for full video', color:C.cyan}
  ];
  steps.forEach((s,i)=>{
    box(slide,s.x,2.18,1.85,1.55,'142337',s.color);
    pill(slide,`0${i+1}`,s.x+0.16,2.38,0.38,s.color);
    text(slide,s.title,s.x+0.16,2.83,1.55,0.25,11,C.white,{bold:true});
    text(slide,s.body,s.x+0.16,3.2,1.55,0.42,9,C.muted);
    if(i<steps.length-1) line(slide,s.x+1.88,2.95,s.x+2.38,2.95,C.muted,1.4);
  });
  box(slide, 0.75, 4.33, 5.6, 1.48, '172536', '2D4B6C');
  text(slide, 'Why save to a temporary file first?', 1.0, 4.57, 5.1, 0.27, 14, C.white, {bold:true});
  text(slide, 'The producer may disappear or delete its source after transfer. The consumer already owns its own received copy, independent of the original.', 1.0, 4.98, 5.05, 0.55, 10, C.muted);
  box(slide, 6.65, 4.33, 5.9, 1.48, '172536', '2D4B6C');
  text(slide, 'Why normalize to MP4?', 6.9, 4.57, 5.3, 0.27, 14, C.white, {bold:true});
  text(slide, 'H.264 video + AAC audio gives the browser a predictable playback format, even when the original was MOV, MKV, AVI, or M4V.', 6.9, 4.98, 5.25, 0.55, 10, C.muted);
  text(slide, 'HTTP serves the GUI and supports byte ranges for seeking. gRPC remains the producer-to-consumer upload channel.', 1.0, 6.22, 11.3, 0.26, 10, C.green, {bold:true, align:'center'});
}

// 7 — Duplicate, idempotency, deletion
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '06 / CORRECTNESS', 'Prevent duplicate work and define deletion behavior', 'The upload ID identifies an attempt; SHA-1 identifies the content. These are deliberately different concepts.');
  box(slide, 0.72, 1.88, 5.72, 2.2, '142337', '345475');
  text(slide, 'IDEMPOTENCY', 0.98, 2.13, 2.1, 0.22, 10, C.cyan, {bold:true, charSpacing:1.4});
  text(slide, 'Same upload_id + same SHA-1', 0.98, 2.52, 4.9, 0.28, 15, C.white, {bold:true});
  text(slide, 'Retry of the same logical attempt. Return the existing state instead of storing a second copy.', 0.98, 2.94, 4.95, 0.55, 10, C.muted);
  text(slide, 'Same upload_id + different SHA-1  →  IDEMPOTENCY_CONFLICT', 0.98, 3.62, 4.95, 0.25, 9, C.yellow, {bold:true});

  box(slide, 6.78, 1.88, 5.78, 2.2, '142B2A', '2F7163');
  text(slide, 'CONTENT DEDUPLICATION', 7.03, 2.13, 2.8, 0.22, 10, C.green, {bold:true, charSpacing:1.2});
  text(slide, 'Different upload_id + same SHA-1', 7.03, 2.52, 5.0, 0.28, 15, C.white, {bold:true});
  text(slide, 'Separate upload intent, but identical file content. Reject as DUPLICATE_CONTENT (or DUPLICATE_IN_PROGRESS).', 7.03, 2.94, 5.0, 0.55, 10, C.muted);
  text(slide, 'Consumer re-computes SHA-1 after receipt; client hash is not trusted blindly.', 7.03, 3.62, 5.0, 0.25, 9, C.green, {bold:true});

  const edgeCases = [
    ['Producer deletes before open', 'Skip + log; send no bytes.', C.yellow],
    ['Producer deletes after upload', 'Consumer copy is independent.', C.green],
    ['Queued upload deleted by consumer', 'Cancel job + remove temporary file.', C.red],
    ['Stored video deleted by consumer', 'Delete MP4 + metadata; remove gallery entry.', C.cyan],
    ['Unsupported extension', 'Reject at producer and consumer.', C.purple]
  ];
  edgeCases.forEach((e,i)=>{
    const yy=4.42+i*0.43;
    slide.addShape(S.ellipse,{x:0.88,y:yy+0.06,w:0.12,h:0.12,fill:{color:e[2]},line:{color:e[2],transparency:100}});
    text(slide,e[0],1.15,yy,3.35,0.22,10,C.white,{bold:true});
    text(slide,e[1],4.65,yy,7.45,0.22,10,C.muted);
  });
  text(slide, 'SHA-1 is included because the assignment specifies it; use SHA-256 for new security-sensitive designs.', 1.1, 6.75, 11.0, 0.18, 8, C.muted, {italic:true, align:'center'});
}

// 8 — Synchronization
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '07 / SYNCHRONIZATION', 'Each shared state has a specific protection', 'The important part is not “using many locks”; it is protecting the exact operations that must remain consistent.');
  const rows = [
    ['ArrayBlockingQueue', 'FIFO order + safe concurrent put/take', 'Many producer RPCs; multiple workers', C.purple],
    ['Semaphore(q)', 'Reserve/release queue admission slots', 'No more than q active admissions', C.cyan],
    ['synchronized(stateLock)', 'Atomic ID/hash checks, cancellation, state changes', 'Avoid duplicate reservations and cancel-vs-worker races', C.green],
    ['ConcurrentHashMap', 'Thread-safe lookup indexes', 'gRPC handlers, workers, HTTP GUI', C.yellow],
    ['synchronized(uploadEntry)', 'Serialize writes and chunk counters', 'One upload stream writes its temp file in order', C.red],
    ['BlockingQueue.take()', 'Worker sleeps until a job exists', 'No polling / busy-waiting', C.cyan],
    ['Atomic file move', 'Publish completed MP4 as one final file', 'GUI never lists partially written videos', C.green]
  ];
  const x=[0.72,3.48,7.18,12.45];
  text(slide,'MECHANISM',0.85,1.86,2.45,0.2,8,C.muted,{bold:true,charSpacing:1});
  text(slide,'GUARANTEE',3.62,1.86,3.35,0.2,8,C.muted,{bold:true,charSpacing:1});
  text(slide,'WHY IT MATTERS',7.32,1.86,4.8,0.2,8,C.muted,{bold:true,charSpacing:1});
  rows.forEach((r,i)=>{
    const yy=2.22+i*0.59;
    box(slide,0.72,yy,11.73,0.48,'121F30','26384D',false);
    pill(slide,'',0.84,yy+0.1,0.08,r[3]);
    text(slide,r[0],1.08,yy+0.11,2.25,0.22,10,C.white,{bold:true});
    text(slide,r[1],3.62,yy+0.11,3.35,0.22,9,C.muted);
    text(slide,r[2],7.32,yy+0.11,4.8,0.22,9,C.white);
  });
  box(slide, 0.72, 6.55, 11.73, 0.35, '1B2C3E', '314D68', false);
  text(slide, 'Worker rule: take from queue → release one semaphore permit → mark PROCESSING → compress → store or fail.', 0.9, 6.62, 11.3, 0.18, 9, C.cyan, {bold:true, align:'center'});
}

// 9 — Build, deployment, testing
{
  const slide = pptx.addSlide('MASTER');
  section(slide, '08 / BUILD & DEPLOY', 'Run the same two apps on different machines', 'Separate Maven projects let the producer and consumer be built and deployed independently.');
  box(slide, 0.72, 1.9, 5.76, 2.1, '142337', '345475');
  text(slide, 'BUILD', 0.98, 2.16, 1.1, 0.22, 10, C.cyan, {bold:true, charSpacing:1.4});
  text(slide, 'JDK 21  +  Maven 3.9.6+', 0.98, 2.55, 4.9, 0.28, 16, C.white, {bold:true});
  text(slide, 'cd consumer  →  mvn clean package\ncd producer  →  mvn clean package', 0.98, 3.0, 4.95, 0.55, 11, C.muted, {fontFace:'Consolas'});
  box(slide, 6.78, 1.9, 5.78, 2.1, '142B2A', '2F7163');
  text(slide, 'RUN', 7.04, 2.16, 1.1, 0.22, 10, C.green, {bold:true, charSpacing:1.4});
  text(slide, 'Consumer first, producer second', 7.04, 2.55, 5.1, 0.28, 16, C.white, {bold:true});
  text(slide, 'Consumer: java -jar consumer-1.0.0.jar config.properties\nProducer: java -jar producer-1.0.0.jar config.properties', 7.04, 3.0, 5.08, 0.55, 9, C.muted, {fontFace:'Consolas'});

  text(slide, 'NETWORK SETUP', 0.85, 4.38, 2.2, 0.22, 9, C.cyan, {bold:true, charSpacing:1.5});
  box(slide, 0.72, 4.75, 3.72, 1.35, '142337', '345475');
  text(slide, 'Docker Compose', 0.95, 4.98, 3.2, 0.24, 14, C.white, {bold:true});
  text(slide, 'Same Docker network: producer uses host name “consumer”.', 0.95, 5.34, 3.18, 0.42, 9, C.muted);
  box(slide, 4.82, 4.75, 3.72, 1.35, '142B2A', '2F7163');
  text(slide, 'VirtualBox / same LAN', 5.05, 4.98, 3.2, 0.24, 14, C.white, {bold:true});
  text(slide, 'Use bridged adapters; producer targets consumer VM LAN IP.', 5.05, 5.34, 3.18, 0.42, 9, C.muted);
  box(slide, 8.92, 4.75, 3.72, 1.35, '26233A', '5B4D80');
  text(slide, 'Different networks', 9.15, 4.98, 3.2, 0.24, 14, C.white, {bold:true});
  text(slide, 'Use private VPN (preferred) or route/port-forward TCP 50051.', 9.15, 5.34, 3.18, 0.42, 9, C.muted);
  pill(slide, '50051  gRPC', 1.2, 6.42, 1.2, C.green);
  pill(slide, '8080  GALLERY', 2.55, 6.42, 1.45, C.cyan);
  text(slide, 'Plaintext demo: keep ports private. Do not expose publicly without TLS and authentication.', 4.35, 6.44, 7.8, 0.2, 9, C.yellow, {bold:true});
}

(async () => {
  const out = '/mnt/data/media-upload-system/Media_Upload_System_Implementation.pptx';
  await pptx.writeFile({ fileName: out });
  console.log(`Wrote ${out}`);
})();
