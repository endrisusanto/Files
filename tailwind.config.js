export default {
  darkMode: ['selector', '[data-theme="dark"]'],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Inter"', 'sans-serif'],
        mono: ['"JetBrainsMono Nerd Font"', '"FiraCode Nerd Font"', '"Hack Nerd Font"', "monospace"],
      },
    },
  },
  plugins: [],
};
