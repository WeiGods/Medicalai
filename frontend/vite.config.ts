import { defineConfig } from 'vitest/config'; import vue from '@vitejs/plugin-vue';
export default defineConfig({ plugins:[vue()], server:{ host:'0.0.0.0', port:5174, strictPort:true, proxy:{'/api':'http://127.0.0.1:8189'} }, test:{ environment:'happy-dom' } });
