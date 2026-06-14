const fs = require('fs');
const { Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, 
        Table, TableRow, TableCell, BorderStyle, WidthType, ShadingType, VerticalAlign,
        LevelFormat } = require('docx');

const tableBorder = { style: BorderStyle.SINGLE, size: 1, color: "CCCCCC" };
const cb = { top: tableBorder, bottom: tableBorder, left: tableBorder, right: tableBorder };

function headerCell(text, width) {
  return new TableCell({
    borders: cb, width: { size: width, type: WidthType.DXA },
    shading: { fill: "2B5797", type: ShadingType.CLEAR },
    verticalAlign: VerticalAlign.CENTER,
    children: [new Paragraph({ alignment: AlignmentType.CENTER,
      children: [new TextRun({ text, bold: true, color: "FFFFFF", size: 22 })] })]
  });
}

function cell(text, width, opts = {}) {
  return new TableCell({
    borders: cb, width: { size: width, type: WidthType.DXA },
    verticalAlign: VerticalAlign.CENTER,
    children: [new Paragraph({ 
      alignment: opts.center ? AlignmentType.CENTER : AlignmentType.LEFT,
      children: [new TextRun({ text, size: 20, ...opts })] })]
  });
}

const doc = new Document({
  styles: {
    default: { document: { run: { font: "Microsoft YaHei", size: 22 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 36, bold: true, color: "2B5797", font: "Microsoft YaHei" },
        paragraph: { spacing: { before: 360, after: 200 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 28, bold: true, color: "2B5797", font: "Microsoft YaHei" },
        paragraph: { spacing: { before: 240, after: 160 }, outlineLevel: 1 } },
    ]
  },
  numbering: { config: [
    { reference: "steps", levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] },
    { reference: "bullets", levels: [{ level: 0, format: LevelFormat.BULLET, text: "\u2022", alignment: AlignmentType.LEFT,
      style: { paragraph: { indent: { left: 720, hanging: 360 } } } }] }
  ]},
  sections: [{
    properties: { page: { margin: { top: 1440, right: 1200, bottom: 1440, left: 1200 } } },
    children: [
      // 标题
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("UNM-Suite \u7f51\u6613\u4e91\u89e3\u9501\u4e09\u7aef\u5957\u4ef6")] }),
      new Paragraph({ spacing: { after: 200 }, children: [
        new TextRun({ text: "\u90e8\u7f72\u4e0e\u4f7f\u7528\u6307\u5357", size: 24, color: "666666" })
      ]}),

      // 架构概览
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("\u67b6\u6784\u6982\u89c8")] }),
      new Paragraph({ spacing: { after: 200 }, children: [
        new TextRun("\u672c\u5957\u4ef6\u5305\u542b\u4e09\u4e2a\u5b50\u7cfb\u7edf\uff0c\u5206\u522b\u5bf9\u5e94 NAS \u670d\u52a1\u7aef\u3001Android \u5ba2\u6237\u7aef\u548c iOS \u5ba2\u6237\u7aef\uff1a")
      ]}),

      new Table({
        columnWidths: [2000, 3200, 4160],
        rows: [
          new TableRow({ tableHeader: true, children: [
            headerCell("\u7ec4\u4ef6", 2000), headerCell("\u529f\u80fd", 3200), headerCell("\u6280\u672f\u65b9\u6848", 4160)
          ]}),
          new TableRow({ children: [
            cell("NAS \u670d\u52a1\u7aef", 2000, {bold:true}),
            cell("Docker \u90e8\u7f72 UNM \u4ee3\u7406\u670d\u52a1", 3200),
            cell("Docker + UnblockNeteaseMusic enhanced", 4160)
          ]}),
          new TableRow({ children: [
            cell("Android \u5ba2\u6237\u7aef", 2000, {bold:true}),
            cell("\u672c\u5730\u97f3\u6e90\u66ff\u6362\uff08\u4e0d\u9700\u670d\u52a1\u5668\uff09", 3200),
            cell("\u675c\u6bd4\u5927\u5587\u53ed\u03b2\u9002\u914d\u65b0\u7248 + Xposed", 4160)
          ]}),
          new TableRow({ children: [
            cell("iOS \u5ba2\u6237\u7aef", 2000, {bold:true}),
            cell("UNM \u4ee3\u7406\u670d\u52a1\u8f6c\u53d1", 3200),
            cell("dylib \u6ce8\u5165 + NSURLSession Hook", 4160)
          ]}),
        ]
      }),

      // NAS服务端部署
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("1. NAS \u670d\u52a1\u7aef\u90e8\u7f72")] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.1 \u524d\u7f6e\u6761\u4ef6")] }),
      ...["NAS \u5df2\u5b89\u88c5 Docker + Docker Compose", "\u670d\u52a1\u5668 IP \u6216\u57df\u540d\u53ef\u8fbe\uff08\u5c40\u57df\u7f51\u6216\u516c\u7f51\u5747\u53ef\uff09"].map(t =>
        new Paragraph({ numbering: { reference: "bullets", level: 0 }, children: [new TextRun(t)] })
      ),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.2 \u4e00\u952e\u90e8\u7f72")] }),
      new Paragraph({ spacing: { after: 200 }, children: [
        new TextRun("\u5c06 server/ \u76ee\u5f55\u4e0a\u4f20\u5230 NAS\uff0c\u7136\u540e\u6267\u884c\uff1a")
      ]}),
      new Paragraph({ spacing: { after: 200 }, shading: { fill: "F2F2F2", type: ShadingType.CLEAR },
        children: [new TextRun({ text: "bash deploy.sh", font: "Consolas", size: 20 })] }),
      
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("1.3 \u81ea\u5b9a\u4e49\u914d\u7f6e")] }),
      new Paragraph({ spacing: { after: 200 }, children: [
        new TextRun("\u7f16\u8f91 docker-compose.yml \u4e2d\u7684\u73af\u5883\u53d8\u91cf\uff1a")
      ]}),

      new Table({
        columnWidths: [2600, 2500, 4260],
        rows: [
          new TableRow({ tableHeader: true, children: [
            headerCell("\u53d8\u91cf", 2600), headerCell("\u9ed8\u8ba4\u503c", 2500), headerCell("\u8bf4\u660e", 4260)
          ]}),
          ...[
            ["ENABLE_FLAC", "true", "\u542f\u7528\u65e0\u635f\u97f3\u8d28\u83b7\u53d6"],
            ["ENABLE_LOCAL_VIP", "svip", "\u672c\u5730\u9ed1\u80f6VIP\u7b49\u7ea7"],
            ["MIN_BR", "320000", "\u6700\u4f4e\u97f3\u8d28 320kbps"],
            ["\u97f3\u6e90\u914d\u7f6e", "kuwo kugou migu...", "\u7528 -o \u53c2\u6570\u6307\u5b9a\u97f3\u6e90\u987a\u5e8f"],
          ].map(([a,b,c]) => new TableRow({ children: [cell(a,2600,{bold:true}), cell(b,2500), cell(c,4260)] }))
        ]
      }),

      // Android客户端
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("2. Android \u5ba2\u6237\u7aef\uff08\u675c\u6bd4\u5927\u5587\u53ed\u03b2\u9002\u914d\u7248\uff09")] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.1 \u9002\u914d\u5185\u5bb9")] }),
      ...["ClassHelper: \u65b0\u589e versionCode >= 800\uff08\u7f51\u6613\u4e91 9.x+\uff09\u7684\u5305\u540d\u6a21\u5f0f\u5339\u914d", 
           "ProxyHook: \u589e\u5f3a OkHttp RealCall \u7c7b\u540d\u67e5\u627e\u5bb9\u9519\uff08dex \u626b\u63cf\u515c\u5e95\uff09",
           "ProxyHook: cronet \u62e6\u622a\u5668\u7c7b\u540d\u5339\u914d\u66f4\u5bbd\u6cdb",
           "ClassHelper.Cookie: \u589e\u52a0\u5b50\u7c7b\u67e5\u627e\u5bb9\u9519\uff08orElse\u515c\u5e95\uff09",
           "\u5185\u5d4c UNM \u66f4\u65b0\u81f3\u6700\u65b0 enhanced \u5206\u652f",
           "compileSdkVersion \u63d0\u5347\u81f3 34\uff0c\u7248\u672c\u53f7 4.0.0-unm-suite"
      ].map(t =>
        new Paragraph({ numbering: { reference: "bullets", level: 0 }, children: [new TextRun(t)] })
      ),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.2 \u7f16\u8bd1\u4e0e\u5b89\u88c5")] }),
      ...["\u5c06 android/dolby_beta_src \u5bfc\u5165 Android Studio", "Gradle Sync \u540e Build APK", "\u5b89\u88c5 APK + \u5728 LSPosed \u4e2d\u542f\u7528\u6a21\u5757", "\u52fe\u9009\u7f51\u6613\u4e91\u97f3\u4e50\u4f5c\u4e3a\u4f5c\u7528\u57df", "\u5f3a\u5236\u505c\u6b62\u7f51\u6613\u4e91\u97f3\u4e50\u540e\u91cd\u65b0\u6253\u5f00"].map((t, i) =>
        new Paragraph({ numbering: { reference: "steps", level: 0 }, children: [new TextRun(t)] })
      ),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("2.3 \u4f7f\u7528\u6a21\u5f0f")] }),
      new Table({
        columnWidths: [2400, 3480, 3480],
        rows: [
          new TableRow({ tableHeader: true, children: [
            headerCell("\u6a21\u5f0f", 2400), headerCell("\u8bf4\u660e", 3480), headerCell("\u914d\u7f6e", 3480)
          ]}),
          new TableRow({ children: [
            cell("\u672c\u5730\u4ee3\u7406\uff08\u9ed8\u8ba4\uff09", 2400, {bold:true}),
            cell("\u624b\u673a\u672c\u5730\u8dd1 UNM\uff0c\u4e0d\u9700\u670d\u52a1\u5668", 3480),
            cell("\u5173\u95ed\u201c\u670d\u52a1\u5668\u4ee3\u7406\u201d\u5f00\u5173", 3480)
          ]}),
          new TableRow({ children: [
            cell("\u670d\u52a1\u5668\u4ee3\u7406", 2400, {bold:true}),
            cell("\u8fdc\u7a0b NAS \u4e0a\u7684 UNM \u670d\u52a1", 3480),
            cell("\u5f00\u542f\u201c\u670d\u52a1\u5668\u4ee3\u7406\u201d + \u586b\u5199 NAS IP/\u7aef\u53e3", 3480)
          ]}),
        ]
      }),

      // iOS客户端
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("3. iOS \u5ba2\u6237\u7aef\uff08UNMHook dylib\uff09")] }),
      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.1 \u5de5\u4f5c\u539f\u7406")] }),
      ...["Hook NSURLSessionConfiguration \u6ce8\u5165 HTTP \u4ee3\u7406\uff0c\u5c06\u7f51\u6613\u4e91\u6d41\u91cf\u5bfc\u5411 NAS UNM \u670d\u52a1",
           "\u4fe1\u4efb UNM \u81ea\u7b7e\u8bc1\u4e66\uff0c\u5141\u8bb8 HTTPS MITM",
           "\u652f\u6301\u4e24\u79cd\u6ce8\u5165\u65b9\u5f0f\uff1a\u5de8\u9b54\uff08TrollStore\uff09+\u8d8a\u72f1"].map(t =>
        new Paragraph({ numbering: { reference: "bullets", level: 0 }, children: [new TextRun(t)] })
      ),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.2 \u7f16\u8bd1\u6b65\u9aa4")] }),
      ...["\u5728 macOS \u4e0a\u5b89\u88c5 Theos", "\u4fee\u6539 Tweak.x \u9876\u90e8\u914d\u7f6e\uff1aPROXY_HOST \u6539\u4e3a NAS IP/\u57df\u540d", "cd ios/UNMHook && make clean && make package", "\u751f\u6210\u7684 deb \u5728 packages/ \u76ee\u5f55"].map((t, i) =>
        new Paragraph({ numbering: { reference: "steps", level: 0 }, children: [new TextRun(t)] })
      ),

      new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun("3.3 \u6ce8\u5165\u65b9\u5f0f")] }),
      new Table({
        columnWidths: [2000, 3680, 3680],
        rows: [
          new TableRow({ tableHeader: true, children: [
            headerCell("\u65b9\u5f0f", 2000), headerCell("\u6b65\u9aa4", 3680), headerCell("\u6ce8\u610f", 3680)
          ]}),
          new TableRow({ children: [
            cell("\u5de8\u9b54", 2000, {bold:true}),
            cell("\u89e3\u538b deb \u83b7\u53d6 dylib\uff0c\u7528 TrollStore Helper \u6ce8\u5165\u7f51\u6613\u4e91 IPA", 3680),
            cell("\u9700\u8981\u91cd\u7b7e\u540d IPA", 3680)
          ]}),
          new TableRow({ children: [
            cell("\u8d8a\u72f1", 2000, {bold:true}),
            cell("dpkg -i \u5b89\u88c5 deb\uff0c\u81ea\u52a8\u751f\u6548", 3680),
            cell("\u9700\u8988\u8d8a\u72f1\u73af\u5883", 3680)
          ]}),
        ]
      }),

      // 故障排除
      new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun("4. \u6545\u969c\u6392\u9664")] }),
      new Table({
        columnWidths: [3120, 3120, 3120],
        rows: [
          new TableRow({ tableHeader: true, children: [
            headerCell("\u95ee\u9898", 3120), headerCell("\u53ef\u80fd\u539f\u56e0", 3120), headerCell("\u89e3\u51b3\u65b9\u6848", 3120)
          ]}),
          ...[
            ["Android \u6a21\u5757\u4e0d\u751f\u6548", "LSPosed \u672a\u52fe\u9009\u7f51\u6613\u4e91", "\u91cd\u65b0\u52fe\u9009 + \u5f3a\u5236\u505c\u6b62\u7f51\u6613\u4e91"],
            ["\u672c\u5730\u4ee3\u7406\u811a\u672c\u62a5\u9519", "libnode.so \u67b6\u6784\u4e0d\u5339\u914d", "\u786e\u8ba4\u624b\u673a\u662f arm64"],
            ["\u7f51\u6613\u4e91\u7248\u672c\u4e0d\u517c\u5bb9", "9.x+ \u7c7b\u540d\u6df7\u6dc6\u89c4\u5219\u53d8\u5316", "\u67e5\u770b Xposed \u65e5\u5fd7\u5b9a\u4f4d\u5931\u8d25\u7684\u7c7b"],
            ["iOS \u8fde\u63a5\u5931\u8d25", "\u4ee3\u7406\u670d\u52a1\u672a\u542f\u52a8", "\u68c0\u67e5 NAS Docker \u72b6\u6001"],
            ["iOS \u8bc1\u4e66\u4fe1\u4efb\u5931\u8d25", "\u672a\u5b89\u88c5 CA \u8bc1\u4e66", "\u5bfc\u5165 ca.crt \u5e76\u542f\u7528\u4fe1\u4efb"],
            ["\u90e8\u5206\u6b4c\u66f2\u65e0\u6cd5\u89e3\u9501", "\u97f3\u6e90\u5339\u914d\u5931\u8d25", "\u66f4\u6362\u97f3\u6e90\u987a\u5e8f\u6216\u4f7f\u7528 pyncmd"],
          ].map(([a,b,c]) => new TableRow({ children: [cell(a,3120), cell(b,3120), cell(c,3120)] }))
        ]
      }),

      new Paragraph({ spacing: { before: 400 }, children: [
        new TextRun({ text: "\u9879\u76ee\u5730\u5740\uff1aunm-suite/", size: 20, color: "666666" })
      ]}),
    ]
  }]
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync("C:\\Users\\zhp\\.local\\share\\teleai-super-agent\\TeleClaw\u7684\u5de5\u4f5c\u7a7a\u95f4\\unm-suite\\docs\\\u90e8\u7f72\u4e0e\u4f7f\u7528\u6307\u5357.docx", buf);
  console.log("done");
});
