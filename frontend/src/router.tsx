import { createBrowserRouter } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import MyPage from './pages/MyPage'
import SubscribePage from './pages/SubscribePage'
import AdminPage from './pages/AdminPage'
import SupplyPage from './pages/SupplyPage'
import SubscribedRoute from './components/guards/SubscribedRoute'
import AdminRoute from './components/guards/AdminRoute'

export const router = createBrowserRouter([
  {
    element: <SubscribedRoute><AppLayout /></SubscribedRoute>,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/supply', element: <SupplyPage /> },
      { path: '/my', element: <MyPage /> },
      { path: '/subscribe', element: <SubscribePage /> },
      { path: '/admin', element: <AdminRoute><AdminPage /></AdminRoute> },
    ],
  },
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
])
