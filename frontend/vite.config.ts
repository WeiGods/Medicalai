import { defineConfig } from 'vite'; import vue from '@vitejs/plugin-vue';
export default defineConfig({ publicDir:'../backend/src/main/resources/fonts', plugins:[vue()], server:{ host:'0.0.0.0', port:5174, strictPort:true, proxy:{'/api':'http://127.0.0.1:8189'} } });
