import asyncio,sys,re
from playwright.async_api import async_playwright
P='/tmp/claude-0/-home-claude/d6955173-0c7a-52cd-aae3-68a24d0196fa/scratchpad/artifact-files/bbd15803-c25e-4d0f-b2eb-1c25a1992e3c/project/'
async def main(names):
    async with async_playwright() as p:
        b=await p.chromium.launch()
        for n in names:
            s=open(P+n+'.dc.html').read()
            s=s.replace('<script src="./support.js"></script>','')
            open(f'dshots/{n}.html','w').write(s)
            pg=await b.new_page(viewport={'width':1440,'height':900})
            await pg.goto(f'file:///tmp/claude-0/-home-claude/d6955173-0c7a-52cd-aae3-68a24d0196fa/scratchpad/dshots/{n}.html'); await pg.wait_for_timeout(1500)
            await pg.screenshot(path=f'dshots/{n}.png',full_page=True); await pg.close()
        await b.close()
asyncio.run(main(sys.argv[1:]))
