import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles.css';
import './workbench.css';
import './analysis/analysis.css';
import './optimization/optimization.css';
import './studio.css';
import './editing.css';
import './scratch.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><App /></React.StrictMode>,
);
